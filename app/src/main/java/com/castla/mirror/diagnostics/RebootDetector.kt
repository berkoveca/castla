package com.castla.mirror.diagnostics

import kotlin.math.abs

/**
 * Identifies one boot of the device. [bootCount] is `Settings.Global.BOOT_COUNT`
 * (null when the device doesn't provide it); [bootWallMs] is
 * `System.currentTimeMillis() - SystemClock.elapsedRealtime()`, i.e. the wall
 * clock time the device booted.
 */
data class BootState(val bootCount: Int?, val bootWallMs: Long)

/** What the previous app process left behind on disk. */
data class Breadcrumb(
    val boot: BootState,
    val pid: Int,
    val sessionActive: Boolean,
    val lastHeartbeatWallMs: Long?,
    val lastHeartbeat: String?
)

enum class RebootVerdict {
    FIRST_RUN,
    /** Same boot, previous process ended normally (or had no session). */
    SAME_BOOT,
    /** The phone rebooted, but no mirroring session was running at the time. */
    REBOOTED,
    /** The phone rebooted while a mirroring session was active. */
    REBOOTED_DURING_SESSION,
    /** Same boot, but the previous app process died without ending its session. */
    PROCESS_DIED_DURING_SESSION
}

object RebootDetector {

    /** Wall-clock jitter tolerated when boot counts are unavailable (NTP adjustments). */
    const val BOOT_WALL_TOLERANCE_MS = 120_000L

    fun isSameBoot(prev: BootState, now: BootState): Boolean {
        if (prev.bootCount != null && now.bootCount != null) return prev.bootCount == now.bootCount
        return abs(prev.bootWallMs - now.bootWallMs) <= BOOT_WALL_TOLERANCE_MS
    }

    fun evaluate(prev: Breadcrumb?, now: BootState, currentPid: Int): RebootVerdict {
        if (prev == null) return RebootVerdict.FIRST_RUN
        if (!isSameBoot(prev.boot, now)) {
            return if (prev.sessionActive) RebootVerdict.REBOOTED_DURING_SESSION else RebootVerdict.REBOOTED
        }
        return if (prev.sessionActive && prev.pid != currentPid) {
            RebootVerdict.PROCESS_DIED_DURING_SESSION
        } else {
            RebootVerdict.SAME_BOOT
        }
    }
}
