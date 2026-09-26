package com.castla.mirror.diagnostics

/**
 * Pure rules for tracing calls into the Shizuku privileged service — every one
 * of which ends up in system_server (display manager, window manager, input
 * dispatcher, activity manager). When the phone soft-reboots, the last "→" line
 * without a matching "←" names the call that was in flight.
 */
object PrivilegedCallTrace {

    enum class Level {
        /** Logged + fsync'ed BEFORE the call, and logged after it. */
        RISKY,
        /** Logged after the call. */
        NORMAL,
        /** Read-only polling: logged only if slow or failing. */
        QUIET,
        /** Injected touch: aggregated by [TouchAggregator]. */
        TOUCH,
        /** Never logged (liveness checks, binder plumbing). */
        SILENT
    }

    const val SLOW_CALL_MS = 1_000L
    private const val MAX_ARG_CHARS = 120

    private val RISKY_METHODS = setOf(
        "createVirtualDisplay", "releaseVirtualDisplay", "resizeVirtualDisplay", "setSurface",
        "launchAppOnDisplay", "launchAppWithExtraOnDisplay", "launchHomeOnDisplay",
        "setPhysicalDisplayPower", "startSystemAudioCapture", "stopSystemAudioCapture",
        "destroy", "registerDeathToken", "setupTeslaNetworking",
        "addInterfaceAddress", "removeInterfaceAddress"
    )
    private val SILENT_METHODS = setOf("isAlive", "asBinder", "hashCode", "equals", "toString")
    private val TEXT_METHODS = setOf("injectText", "injectComposingText")

    private val RISKY_COMMANDS = listOf("am ", "cmd ", "input ", "wm ", "pm grant", "appops ", "kill ")
    private val QUIET_COMMANDS = listOf("dumpsys ", "getprop", "pm path", "settings get", "cat ", "ls ", "ps ", "pidof ", "id")

    fun levelOf(method: String, args: Array<out Any?>?): Level = when {
        method in SILENT_METHODS -> Level.SILENT
        method == "injectInput" || method == "injectMotionEvent" -> Level.TOUCH
        method in RISKY_METHODS -> Level.RISKY
        method == "execCommand" -> commandLevel(args?.firstOrNull() as? String)
        else -> Level.NORMAL
    }

    private fun commandLevel(command: String?): Level {
        val c = command?.trimStart() ?: return Level.NORMAL
        if ('\n' in c) return Level.NORMAL // scripts (post-mortem): one line after completion
        if (c.startsWith("dumpsys power set-display-state")) return Level.RISKY
        if (QUIET_COMMANDS.any { c.startsWith(it) }) return Level.QUIET
        if (RISKY_COMMANDS.any { c.startsWith(it) }) return Level.RISKY
        return Level.NORMAL
    }

    fun isSlow(durationMs: Long): Boolean = durationMs >= SLOW_CALL_MS

    /** `method(arg, arg)` with typed text reduced to its length and long strings shortened. */
    fun describeCall(method: String, args: Array<out Any?>?): String {
        val parts = args.orEmpty().map { arg ->
            when {
                method in TEXT_METHODS && arg is String -> "<${arg.length} chars>"
                arg is String -> shorten(arg)
                arg == null -> "null"
                arg is Number || arg is Boolean -> arg.toString()
                else -> arg.javaClass.simpleName
            }
        }
        return "$method(${parts.joinToString(", ")})"
    }

    fun describeResult(result: Any?): String = when (result) {
        null -> ""
        is Unit -> ""
        is String -> " = " + shorten(result.ifBlank { "<empty>" })
        is Number, is Boolean -> " = $result"
        else -> " = " + result.javaClass.simpleName
    }

    private fun shorten(s: String): String {
        val lines = s.lines()
        val first = lines.first().trim()
        val extra = lines.size - 1
        val head = if (first.length > MAX_ARG_CHARS) first.take(MAX_ARG_CHARS) + "…" else first
        return if (extra > 0) "'$head' (+$extra lines)" else "'$head'"
    }

    /**
     * Injected touch reaches system_server's input dispatcher. Logs each finger
     * DOWN/UP and summarizes MOVEs, so a crash mid-gesture is visible without
     * writing a line per move event.
     */
    class TouchAggregator(private val summaryEveryMs: Long = 5_000) {
        private var moves = 0
        private var windowStartMs = 0L

        /** Returns a line to log, or null. Actions: 0 DOWN, 1 UP, 2 MOVE, 3 CANCEL, 5/6 POINTER_DOWN/UP. */
        @Synchronized
        fun onTouch(displayId: Int, action: Int, x: Float, y: Float, pointerId: Int, nowMs: Long, pointerCount: Int = 1): String? {
            val at = "(${Math.round(x)},${Math.round(y)})" + if (pointerCount > 1) " n=$pointerCount" else ""
            return when (action and 0xff) {
                2 -> {
                    moves++
                    if (nowMs - windowStartMs >= summaryEveryMs) {
                        val line = "touch MOVE d=$displayId moves=$moves in ${nowMs - windowStartMs}ms"
                        moves = 0
                        windowStartMs = nowMs
                        line
                    } else null
                }
                0, 5 -> {
                    moves = 0
                    windowStartMs = nowMs
                    "touch ${if (action and 0xff == 0) "DOWN" else "POINTER_DOWN"} d=$displayId id=$pointerId $at"
                }
                1, 6, 3 -> {
                    val name = when (action and 0xff) { 1 -> "UP"; 6 -> "POINTER_UP"; else -> "CANCEL" }
                    val line = "touch $name d=$displayId id=$pointerId $at moves=$moves"
                    moves = 0
                    windowStartMs = nowMs
                    line
                }
                else -> "touch action=$action d=$displayId id=$pointerId $at"
            }
        }
    }
}
