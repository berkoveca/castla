package com.castla.mirror.diagnostics

import android.os.SystemClock
import android.util.Log

/**
 * Structured diagnostic events emitted during a mirroring session.
 * Each event is logged with a consistent tag and prefix for easy filtering.
 */
enum class DiagnosticEvent {
    SCREEN_OFF,
    SCREEN_ON,
    KEYGUARD_LOCKED,
    KEYGUARD_UNLOCKED,
    SHIZUKU_BINDER_DEAD,
    SHIZUKU_BINDER_READY,
    /** Informational: process fortification applied (doze whitelist, appops, oom_adj). */
    SHIZUKU_FORTIFIED,
    VD_CREATED,
    VD_STOPPED,
    SOCKET_DISCONNECTED,
    SOCKET_TIMEOUT,
    SESSION_END,
    /** Advertised IP chosen, with full candidate snapshot (iface/maskedIp/priority). */
    URL_SELECTED,
    /** HTTP server started; detail carries the loopback self-probe result. */
    SERVER_READY,
    /** First non-loopback HTTP request of the session (masked source IP + sanitized Host). */
    HTTP_FIRST_CONTACT,
    /** Browser socket count transitioned 0→1 (first contact or reconnect). */
    WS_CONNECTED,
    /**
     * Per-socket open, one event per channel (video/secondary/control/audio),
     * with the masked remote IP. Distinguishes "the same client reconnecting
     * repeatedly" from "several different clients/ports flapping" — the
     * aggregate WS_CONNECTED/SOCKET_DISCONNECTED pair can't tell those apart.
     */
    SOCKET_OPENED,
    /**
     * Per-socket close, one event per channel, carrying the WebSocket close
     * code/reason and whether the remote end (vs. this app) initiated it.
     * A clean close (code+reason present, initiatedByRemote=true) means the
     * BROWSER hung up on purpose; an abrupt close with no code/reason usually
     * means the underlying TCP connection died before a close frame could be
     * sent — i.e. a network-layer drop, not an app or browser decision.
     */
    SOCKET_CLOSED,
    /**
     * Per-socket transport exception (as opposed to a clean WebSocket close).
     * Carries the exception class + message, e.g. "SocketTimeoutException" vs.
     * "ConnectionResetException" — the two look identical as a bare
     * SOCKET_DISCONNECTED but point at very different root causes.
     */
    SOCKET_EXCEPTION,
    /**
     * The main HTML page was served (every time, not just first contact).
     * Distinguishes "the browser genuinely reloaded the page N times" from
     * "sockets reconnected without a page reload" — the two produce identical
     * WS_CONNECTED/SOCKET_DISCONNECTED churn but need very different fixes.
     */
    PAGE_LOAD
}

/**
 * High-level cause categories for session disconnects.
 * Used in the final SESSION_END event to classify *why* the session ended.
 */
enum class DisconnectCause {
    /** Shizuku binder died or PrivilegedService became unreachable. */
    SHIZUKU,
    /** Virtual display was released or became invalid. */
    VIRTUAL_DISPLAY,
    /** All browser sockets disconnected (network issue or browser closed). */
    NETWORK,
    /** App process killed or device entered deep sleep despite wake locks. */
    PROCESS_OR_POWER,
    /** Could not determine cause from available signals. */
    UNKNOWN
}

/**
 * Specific terminal failure reasons emitted by the service when a known silent-fail
 * path is hit. Persisted to disk via [FileLogger] so post-mortem diagnosis is possible.
 */
enum class TerminalReason {
    VD_RECREATE_FAILED,
    SHIZUKU_REBIND_FAILED,
    PIPELINE_REBUILD_EXCEPTION,
    BROWSER_ACTIVATION_FAILED,
}

/**
 * Pure classifier — given the set of recent diagnostic events that preceded a
 * session end, returns the most likely [DisconnectCause].
 *
 * Walks the event list from most recent to oldest. Recovery events
 * (`SHIZUKU_BINDER_READY`, `VD_CREATED`) cancel out their corresponding
 * failure events, so a successful reconnect/rebuild does not contaminate
 * classification. The first *unrecovered* strong signal wins:
 *
 *  1. Unrecovered `SHIZUKU_BINDER_DEAD`              → SHIZUKU
 *  2. Unrecovered `VD_STOPPED`                        → VIRTUAL_DISPLAY
 *  3. `SOCKET_DISCONNECTED` or `SOCKET_TIMEOUT` not followed by `WS_CONNECTED` → NETWORK
 *  4. `SCREEN_OFF` present (with no strong signal)     → PROCESS_OR_POWER
 *  5. fallback                                         → UNKNOWN
 *
 * Informational events like `SHIZUKU_FORTIFIED` are ignored by the classifier
 * and must not affect the outcome.
 */
object DisconnectCauseClassifier {

    fun classify(recentEvents: List<DiagnosticEvent>): DisconnectCause {
        // Track how many recovery events we've seen while walking backwards.
        // Each recovery "absorbs" one preceding failure of the same kind.
        var shizukuRecoveries = 0
        var vdRecoveries = 0
        // A reconnect recovers ALL earlier socket failures, not just one: a single
        // outage can emit SOCKET_TIMEOUT then SOCKET_DISCONNECTED back to back.
        var wsRecovered = false

        for (event in recentEvents.asReversed()) {
            when (event) {
                // Recovery events: accumulate a credit that cancels one failure
                DiagnosticEvent.SHIZUKU_BINDER_READY -> shizukuRecoveries++
                DiagnosticEvent.VD_CREATED -> vdRecoveries++
                DiagnosticEvent.WS_CONNECTED -> wsRecovered = true

                // Failure events: only count if not cancelled by a later recovery
                DiagnosticEvent.SHIZUKU_BINDER_DEAD -> {
                    if (shizukuRecoveries > 0) {
                        shizukuRecoveries--
                    } else {
                        return DisconnectCause.SHIZUKU
                    }
                }
                DiagnosticEvent.VD_STOPPED -> {
                    if (vdRecoveries > 0) {
                        vdRecoveries--
                    } else {
                        return DisconnectCause.VIRTUAL_DISPLAY
                    }
                }
                DiagnosticEvent.SOCKET_DISCONNECTED,
                DiagnosticEvent.SOCKET_TIMEOUT -> {
                    if (!wsRecovered) return DisconnectCause.NETWORK
                }

                else -> { /* continue scanning */ }
            }
        }
        // No strong signal — check if screen was off (power/OEM kill suspected)
        if (recentEvents.any { it == DiagnosticEvent.SCREEN_OFF }) {
            return DisconnectCause.PROCESS_OR_POWER
        }
        return DisconnectCause.UNKNOWN
    }
}

/**
 * Central diagnostic logger for mirroring sessions.
 * Logs structured events with a consistent format and maintains a small ring
 * buffer of recent events for disconnect cause classification.
 *
 * Thread-safe: all public methods synchronize on [recentEvents].
 */
object MirrorDiagnostics {

    private const val TAG = "MirrorDiag"
    private const val MAX_RECENT_EVENTS = 32

    private val recentEvents = mutableListOf<DiagnosticEvent>()
    private var sessionStartUptimeMs = 0L
    @Volatile private var sessionActive = false

    /** Call when a new mirroring session starts to reset state. */
    fun onSessionStart() {
        synchronized(recentEvents) {
            recentEvents.clear()
            sessionStartUptimeMs = SystemClock.elapsedRealtime()
            sessionActive = true
        }
        Log.i(TAG, "[SESSION_START]")
        FileLogger.i(TAG, "[SESSION_START]")
    }

    /** Record a diagnostic event with optional detail string. */
    fun log(event: DiagnosticEvent, detail: String? = null) {
        val elapsed = if (sessionActive) SystemClock.elapsedRealtime() - sessionStartUptimeMs else 0L
        synchronized(recentEvents) {
            recentEvents.add(event)
            if (recentEvents.size > MAX_RECENT_EVENTS) {
                recentEvents.removeAt(0)
            }
        }
        val msg = buildString {
            append("[${event.name}]")
            append(" +${elapsed}ms")
            if (detail != null) append(" $detail")
        }
        Log.i(TAG, msg)
        FileLogger.i(TAG, msg)
    }

    /**
     * Classify and log the disconnect cause at session end.
     * No-ops if no session was started (guards against cleanup-before-start).
     * Returns the classified [DisconnectCause], or null if no session was active.
     */
    fun endSession(cleanupReason: String): DisconnectCause? {
        if (!sessionActive) {
            Log.d(TAG, "[SESSION_END_SKIPPED] reason=$cleanupReason (no active session)")
            return null
        }
        val events: List<DiagnosticEvent>
        synchronized(recentEvents) {
            events = recentEvents.toList()
            sessionActive = false
        }
        val cause = DisconnectCauseClassifier.classify(events)
        log(DiagnosticEvent.SESSION_END, "reason=$cleanupReason cause=${cause.name}")
        val summary = "[SESSION_SUMMARY] duration=${SystemClock.elapsedRealtime() - sessionStartUptimeMs}ms " +
                "events=${events.size} cause=${cause.name} recent=${events.takeLast(5).map { it.name }}"
        Log.i(TAG, summary)
        FileLogger.i(TAG, summary)
        return cause
    }
}
