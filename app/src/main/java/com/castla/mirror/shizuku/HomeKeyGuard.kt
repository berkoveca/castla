package com.castla.mirror.shizuku

/**
 * Keeps a virtual display alive until a HOME key sent to it has been handled.
 *
 * PhoneWindowManager handles HOME asynchronously (its per-display handler posts
 * the short press, delayed for double-tap detection). If the display is released
 * in that window, startHomeOnDisplay() dereferences the missing display and
 * system_server dies with a NullPointerException — the phone soft-reboots.
 * Field log: HOME to VD 25, VD 25 released for a resize a few ms later,
 * system_server_crash in RootWindowContainer.startHomeOnDisplay.
 */
class HomeKeyGuard(private val settleMs: Long = DEFAULT_SETTLE_MS) {

    private val lastHomeAt = HashMap<Int, Long>()

    @Synchronized
    fun onHomeSent(displayId: Int, nowMs: Long) {
        lastHomeAt[displayId] = nowMs
    }

    /** How long to wait before [displayId] may be released. */
    @Synchronized
    fun waitBeforeRelease(displayId: Int, nowMs: Long): Long {
        val sentAt = lastHomeAt[displayId] ?: return 0
        return (sentAt + settleMs - nowMs).coerceAtLeast(0)
    }

    @Synchronized
    fun onReleased(displayId: Int) {
        lastHomeAt.remove(displayId)
    }

    companion object {
        /** Well above the 300 ms double-tap timeout, with margin for a busy system_server. */
        const val DEFAULT_SETTLE_MS = 1_000L

        private val HOME_CMD = Regex("""\binput\s+-d\s+(\d+)\s+keyevent\s+(?:3|HOME|KEYCODE_HOME)\b""")

        /** Display targeted by a shell HOME keyevent, or null if [command] is not one. */
        fun homeDisplayOf(command: String): Int? =
            HOME_CMD.find(command)?.groupValues?.get(1)?.toIntOrNull()
    }
}
