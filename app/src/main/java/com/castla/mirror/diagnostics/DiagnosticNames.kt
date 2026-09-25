package com.castla.mirror.diagnostics

/**
 * Human-readable names for the integer constants that show up in diagnostic
 * lines. Plain Kotlin (no android.* references) so it stays unit-testable; the
 * values mirror the platform constants they are named after.
 */
object DiagnosticNames {

    /** PowerManager.THERMAL_STATUS_*. */
    fun thermal(status: Int): String {
        val name = when (status) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
        return "$name($status)"
    }

    /** ApplicationExitInfo.REASON_*. */
    fun exitReason(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_*. */
    fun importance(importance: Int): String = when (importance) {
        100 -> "FOREGROUND"
        125 -> "FOREGROUND_SERVICE"
        150 -> "TOP_SLEEPING_PRE_28"
        200 -> "VISIBLE"
        230 -> "PERCEPTIBLE"
        300 -> "SERVICE"
        325 -> "TOP_SLEEPING"
        350 -> "CANT_SAVE_STATE"
        400 -> "CACHED"
        1000 -> "GONE"
        else -> "IMPORTANCE_$importance"
    }

    /** ComponentCallbacks2.TRIM_MEMORY_*. */
    fun trimMemory(level: Int): String = when (level) {
        5 -> "RUNNING_MODERATE"
        10 -> "RUNNING_LOW"
        15 -> "RUNNING_CRITICAL"
        20 -> "UI_HIDDEN"
        40 -> "BACKGROUND"
        60 -> "MODERATE"
        80 -> "COMPLETE"
        else -> "LEVEL_$level"
    }

    private val VM_RSS = Regex("""(?m)^VmRSS:\s+(\d+)\s+kB""")

    /** Resident set size in kB from the text of `/proc/<pid>/status`. */
    fun parseVmRssKb(procStatus: String): Long? =
        VM_RSS.find(procStatus)?.groupValues?.get(1)?.toLongOrNull()
}
