package com.castla.mirror.policy

/**
 * Keeps the phone from sleeping (and so locking) while the car is mirroring.
 *
 * Field logs: every time the phone's screen timed out mid-session it slept,
 * came back on the lock screen, and the car picture could stay black until the
 * virtual display was recreated. During a session the screen-off timeout is set
 * very long; the user's own value is saved first and put back when the session
 * ends (also after a crash: the saved value survives in preferences).
 */
object ScreenTimeoutPolicy {

    /** Timeout used while mirroring: 24 h, i.e. never within a drive. */
    const val KEEP_AWAKE_MS = 86_400_000L

    /** Restored when our value is found but the user's own one was lost. */
    const val FALLBACK_RESTORE_MS = 60_000L

    /** Parses `settings get system screen_off_timeout` output. */
    fun parse(output: String?): Long? = output?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** The user's own timeout to remember at session start (null = keep what is saved / nothing usable). */
    fun valueToSave(current: Long?, alreadySaved: Long?): Long? = when {
        alreadySaved != null -> null
        current == null || current == KEEP_AWAKE_MS -> null
        else -> current
    }

    /** Value to put back at session end, or null to leave the setting alone. */
    fun valueToRestore(saved: Long?, current: Long?): Long? = when {
        saved != null -> saved.takeIf { it != current }
        current == KEEP_AWAKE_MS -> FALLBACK_RESTORE_MS
        else -> null
    }
}
