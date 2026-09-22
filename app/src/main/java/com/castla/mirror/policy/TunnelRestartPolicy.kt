package com.castla.mirror.policy

/**
 * Pure retry / circuit-breaker policy for the Cloudflare tunnel process.
 *
 * Keeps a dropped connector alive across mobile-network blips without:
 *  - spawning overlapping cloudflared processes (restart storms → heat → reboot)
 *  - giving up after a handful of drops (the previous "tunnel died forever" failure)
 *  - killing the tunnel before the Tesla browser has had time to open the URL
 */
object TunnelRestartPolicy {
    const val MAX_INITIAL_START_RETRIES = 3
    const val INITIAL_START_BASE_DELAY_MS = 2_000L
    const val RESTART_BASE_DELAY_MS = 3_000L
    const val MAX_RESTART_BACKOFF_MS = 30_000L
    const val CIRCUIT_WINDOW_MS = 60_000L
    const val CIRCUIT_MAX_DROPS = 5
    const val CIRCUIT_COOLDOWN_MS = 60_000L
    /** Idle stop starts only AFTER a public URL is ready, not at process spawn. */
    const val IDLE_AFTER_URL_MS = 180_000L
    const val START_TIMEOUT_MS = 60_000L

    fun initialRetryDelayMs(attempt: Int): Long =
        INITIAL_START_BASE_DELAY_MS * attempt.coerceAtLeast(1)

    fun shouldGiveUpInitial(attempt: Int): Boolean =
        attempt >= MAX_INITIAL_START_RETRIES

    fun dropRetryDelayMs(restartCount: Int, dropsInWindow: Int): Long {
        if (dropsInWindow >= CIRCUIT_MAX_DROPS) return CIRCUIT_COOLDOWN_MS
        return (RESTART_BASE_DELAY_MS * restartCount.coerceAtLeast(1))
            .coerceAtMost(MAX_RESTART_BACKOFF_MS)
    }

    fun dropsInWindow(dropTimestampsMs: List<Long>, nowMs: Long): Int =
        dropTimestampsMs.count { ts -> nowMs - ts in 0..CIRCUIT_WINDOW_MS }
}
