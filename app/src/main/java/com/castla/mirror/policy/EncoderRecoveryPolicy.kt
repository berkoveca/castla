package com.castla.mirror.policy

/**
 * Pure recovery policy for MediaCodec / JPEG encoder failures.
 *
 * Native codec errors must not crash the process (that orphans the virtual
 * display and can reboot the phone). Rebuild a few times with backoff, then
 * stop the session cleanly so the SoC can recover.
 */
object EncoderRecoveryPolicy {
    const val MAX_RETRIES = 3
    const val BASE_REBUILD_DELAY_MS = 500L
    const val MAX_REBUILD_DELAY_MS = 8_000L

    fun shouldRebuild(consecutiveErrors: Int): Boolean =
        consecutiveErrors in 0..MAX_RETRIES

    fun shouldStopSession(consecutiveErrors: Int): Boolean =
        consecutiveErrors > MAX_RETRIES

    fun rebuildDelayMs(consecutiveErrors: Int): Long {
        val n = consecutiveErrors.coerceAtLeast(0)
        if (n <= 0) return BASE_REBUILD_DELAY_MS
        val shift = (n - 1).coerceAtMost(4)
        return (BASE_REBUILD_DELAY_MS shl shift).coerceAtMost(MAX_REBUILD_DELAY_MS)
    }
}
