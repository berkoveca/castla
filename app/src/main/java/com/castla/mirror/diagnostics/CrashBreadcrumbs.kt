package com.castla.mirror.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leaves durable breadcrumbs (SharedPreferences, which fsyncs on write) so the
 * NEXT app start can tell what happened to the previous one:
 *
 *  - did the phone reboot since then, and was a mirroring session running?
 *  - did the app process die mid-session without a reboot?
 *  - what did the last health heartbeat look like (thermal, battery, memory)?
 *  - why did Android say our previous processes exited (API 30+)?
 *
 * Results are written to [FileLogger] and kept in memory for the clipboard
 * report ([summaryLines]).
 */
object CrashBreadcrumbs {

    private const val TAG = "Breadcrumbs"
    private const val PREFS = "diag_breadcrumbs"
    private const val K_BOOT_COUNT = "boot_count"
    private const val K_BOOT_WALL = "boot_wall_ms"
    private const val K_PID = "pid"
    private const val K_SESSION = "session_active"
    private const val K_SESSION_REASON = "session_reason"
    private const val K_HB_WALL = "hb_wall_ms"
    private const val K_HB = "hb"
    private const val K_LAST_EXIT_TS = "last_exit_ts"

    @Volatile private var startSummary: List<String> = emptyList()

    fun currentBoot(context: Context): BootState {
        val count = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: Throwable) { -1 }
        return BootState(
            bootCount = count.takeIf { it >= 0 },
            bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        )
    }

    /**
     * Call once per process, early and synchronously (Application.onCreate), so the
     * previous run's breadcrumb is read before a new session can overwrite it.
     * Only small prefs I/O runs inline; the exit-reason query runs on a thread.
     */
    fun onAppStart(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = currentBoot(context)
            val prev = if (prefs.contains(K_BOOT_WALL)) {
                Breadcrumb(
                    boot = BootState(
                        prefs.getInt(K_BOOT_COUNT, -1).takeIf { it >= 0 },
                        prefs.getLong(K_BOOT_WALL, 0L)
                    ),
                    pid = prefs.getInt(K_PID, -1),
                    sessionActive = prefs.getBoolean(K_SESSION, false),
                    lastHeartbeatWallMs = prefs.getLong(K_HB_WALL, 0L).takeIf { it > 0 },
                    lastHeartbeat = prefs.getString(K_HB, null)
                )
            } else null
            val verdict = RebootDetector.evaluate(prev, now, Process.myPid())

            val lines = ArrayList<String>()
            lines += "verdict: $verdict"
            lines += "this boot: bootCount=${now.bootCount ?: "n/a"} bootedAt=${fmt(now.bootWallMs)} " +
                "uptime=${formatDuration(SystemClock.elapsedRealtime())}"
            if (prev != null) {
                lines += "previous run: pid=${prev.pid} bootCount=${prev.boot.bootCount ?: "n/a"} " +
                    "bootedAt=${fmt(prev.boot.bootWallMs)} sessionActive=${prev.sessionActive} " +
                    "sessionReason=${prefs.getString(K_SESSION_REASON, null) ?: "-"}"
                val hbAt = prev.lastHeartbeatWallMs
                if (hbAt != null) {
                    lines += "previous run last heartbeat at ${fmt(hbAt)}: ${prev.lastHeartbeat ?: "-"}"
                    if (verdict == RebootVerdict.REBOOTED || verdict == RebootVerdict.REBOOTED_DURING_SESSION) {
                        lines += "gap between last heartbeat and this boot: " +
                            formatDuration((now.bootWallMs - hbAt).coerceAtLeast(0))
                    }
                }
            }
            when (verdict) {
                RebootVerdict.REBOOTED_DURING_SESSION -> lines +=
                    "!! The PHONE REBOOTED while mirroring was running. See the heartbeat above " +
                        "and the PostMortem lines (boot reason, system_server crashes) for the cause."
                RebootVerdict.PROCESS_DIED_DURING_SESSION -> lines +=
                    "!! The app process DIED mid-session without a reboot (killed or crashed) — " +
                        "see previous app exits below."
                else -> {}
            }
            startSummary = lines
            lines.forEach { FileLogger.i(TAG, it) }

            prefs.edit()
                .putInt(K_BOOT_COUNT, now.bootCount ?: -1)
                .putLong(K_BOOT_WALL, now.bootWallMs)
                .putInt(K_PID, Process.myPid())
                .putBoolean(K_SESSION, false)
                .remove(K_SESSION_REASON)
                .commit()

            Thread({ logNewExitReasons(context) }, "diag-exit-reasons").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            Log.w(TAG, "onAppStart failed", t)
            FileLogger.w(TAG, "breadcrumb evaluation failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Mark a mirroring session as running (true) or cleanly ended (false). */
    fun markSession(context: Context, active: Boolean, reason: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_SESSION, active)
                .putString(K_SESSION_REASON, reason)
                .putInt(K_PID, Process.myPid())
                .commit()
        } catch (t: Throwable) {
            Log.w(TAG, "markSession failed", t)
        }
    }

    /** Store the latest health line so it survives a reboot. */
    fun recordHeartbeat(context: Context, line: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(K_HB_WALL, System.currentTimeMillis())
                .putString(K_HB, line.take(600))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "recordHeartbeat failed", t)
        }
    }

    fun summaryLines(): List<String> = startSummary.ifEmpty { listOf("(not evaluated yet)") }

    /**
     * Recent process exits of this app as recorded by Android (API 30+), newest
     * first. Explains deaths we could not log ourselves: LOW_MEMORY, FREEZER,
     * EXCESSIVE_RESOURCE_USAGE, CRASH_NATIVE, SIGNALED(9) …
     */
    fun exitReasonLines(context: Context, max: Int = 6): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return listOf("(needs Android 11+)")
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            am.getHistoricalProcessExitReasons(context.packageName, 0, max).map { info ->
                buildString {
                    append(fmt(info.timestamp)).append(' ')
                    append(info.processName).append(" pid=").append(info.pid)
                    append(" reason=").append(DiagnosticNames.exitReason(info.reason))
                    append(" status=").append(info.status)
                    append(" importance=").append(DiagnosticNames.importance(info.importance))
                    append(" pss=").append(info.pss / 1024).append("MB")
                    append(" rss=").append(info.rss / 1024).append("MB")
                    info.description?.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(it.take(160)).append('"') }
                }
            }.ifEmpty { listOf("(none recorded)") }
        } catch (t: Throwable) {
            listOf("(unavailable: ${t.javaClass.simpleName})")
        }
    }

    /** Logs exit records that are newer than the last one we logged. */
    private fun logNewExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastLogged = prefs.getLong(K_LAST_EXIT_TS, 0L)
            val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.timestamp > lastLogged }
            if (fresh.isEmpty()) return
            val lines = exitReasonLines(context, 10)
            // exitReasonLines is newest-first; log oldest-first so the file reads chronologically.
            lines.take(fresh.size).asReversed().forEach { FileLogger.w(TAG, "previous app exit: $it") }
            prefs.edit().putLong(K_LAST_EXIT_TS, fresh.maxOf { it.timestamp }).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "logNewExitReasons failed", t)
        }
    }

    fun fmt(wallMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wallMs))

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%dh%02dm%02ds".format(Locale.US, s / 3600, (s % 3600) / 60, s % 60)
    }
}
