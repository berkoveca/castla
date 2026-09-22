package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class TunnelRestartPolicyTest {

    @Test
    fun `initial retry delay grows linearly and stays bounded`() {
        assertEquals(2_000L, TunnelRestartPolicy.initialRetryDelayMs(1))
        assertEquals(4_000L, TunnelRestartPolicy.initialRetryDelayMs(2))
        assertEquals(6_000L, TunnelRestartPolicy.initialRetryDelayMs(3))
    }

    @Test
    fun `initial start gives up after max retries`() {
        assertFalse(TunnelRestartPolicy.shouldGiveUpInitial(0))
        assertFalse(TunnelRestartPolicy.shouldGiveUpInitial(1))
        assertFalse(TunnelRestartPolicy.shouldGiveUpInitial(2))
        assertTrue(TunnelRestartPolicy.shouldGiveUpInitial(3))
        assertTrue(TunnelRestartPolicy.shouldGiveUpInitial(99))
    }

    @Test
    fun `drop backoff grows then caps`() {
        assertEquals(3_000L, TunnelRestartPolicy.dropRetryDelayMs(restartCount = 1, dropsInWindow = 1))
        assertEquals(9_000L, TunnelRestartPolicy.dropRetryDelayMs(restartCount = 3, dropsInWindow = 1))
        assertEquals(30_000L, TunnelRestartPolicy.dropRetryDelayMs(restartCount = 20, dropsInWindow = 1))
    }

    @Test
    fun `circuit breaker stretches delay when drops storm`() {
        val storm = TunnelRestartPolicy.CIRCUIT_MAX_DROPS
        assertEquals(
            TunnelRestartPolicy.CIRCUIT_COOLDOWN_MS,
            TunnelRestartPolicy.dropRetryDelayMs(restartCount = 1, dropsInWindow = storm)
        )
        assertTrue(
            TunnelRestartPolicy.dropRetryDelayMs(restartCount = 1, dropsInWindow = storm) >
                TunnelRestartPolicy.dropRetryDelayMs(restartCount = 1, dropsInWindow = 1)
        )
    }

    @Test
    fun `dropsInWindow counts only recent timestamps`() {
        val now = 100_000L
        val stamps = listOf(
            now - 1_000L,
            now - 30_000L,
            now - 61_000L,
            now - 120_000L
        )
        assertEquals(2, TunnelRestartPolicy.dropsInWindow(stamps, now))
    }

    @Test
    fun `idle timeout after URL is long enough for Tesla browser`() {
        assertTrue(TunnelRestartPolicy.IDLE_AFTER_URL_MS >= 180_000L)
    }

    @Test
    fun `negative or zero restart counts still produce a positive delay`() {
        assertTrue(TunnelRestartPolicy.dropRetryDelayMs(restartCount = 0, dropsInWindow = 0) > 0)
        assertTrue(TunnelRestartPolicy.initialRetryDelayMs(0) > 0)
    }
}
