package com.castla.mirror.policy

/**
 * Exponential backoff for the Tesla-browser WebSocket reconnect loop.
 * Numbers are mirrored in `assets/web/js/main.js` (the browser cannot import this).
 */
object ClientReconnectPolicy {
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 20_000L
    private const val FACTOR = 1.5

    fun delayMs(attempt: Int): Long {
        val n = attempt.coerceAtLeast(0)
        var delay = BASE_DELAY_MS.toDouble()
        repeat(n) { delay *= FACTOR }
        return delay.toLong().coerceAtMost(MAX_DELAY_MS)
    }
}
