package com.castla.mirror.diagnostics

/**
 * Turns a child-process exit code (as returned by [Process.exitValue]) into a
 * human-readable cause. The JVM reports a process killed by signal N as
 * `128 + N`, so 137 is SIGKILL, 143 is SIGTERM, and so on.
 *
 * For cloudflared this is the difference between "Android killed it" (SIGKILL:
 * low-memory killer, phantom-process killer, or the app process — its parent —
 * being killed), "it crashed" (SIGSEGV/SIGABRT/Go panic) and "it gave up on
 * its own" (exit 1 with a fatal log line).
 */
object ProcessExitDecoder {

    fun signalOf(exitCode: Int): Int? =
        if (exitCode in 129..192) exitCode - 128 else null

    fun describe(exitCode: Int): String {
        if (exitCode < 0) return "exit=unknown (still running or exit code unavailable)"
        val signal = signalOf(exitCode)
        val meaning = if (signal != null) signalMeaning(signal) else codeMeaning(exitCode)
        return "exit=$exitCode ($meaning)"
    }

    private fun codeMeaning(code: Int): String = when (code) {
        0 -> "clean exit"
        1 -> "cloudflared reported a fatal error — see the ERR/FTL lines above"
        2 -> "Go runtime panic or fatal error — see the panic line above"
        else -> "exited with code $code"
    }

    private fun signalMeaning(signal: Int): String = when (signal) {
        9 -> "SIGKILL: killed by Android — low-memory killer, phantom-process killer, " +
            "or the app process itself was killed"
        15 -> "SIGTERM: asked to stop — by this app or by the system"
        6 -> "SIGABRT: aborted (crash)"
        11 -> "SIGSEGV: segmentation fault (crash)"
        7 -> "SIGBUS: bus error (crash)"
        4 -> "SIGILL: illegal instruction — wrong CPU architecture build?"
        13 -> "SIGPIPE: its output pipe was closed"
        31 -> "SIGSYS: blocked system call — Android seccomp filter; " +
            "binary was not built for Android"
        else -> "killed by signal $signal"
    }
}
