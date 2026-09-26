package com.castla.mirror.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginThrottleTest {

    @Test
    fun `five failures lock that client for fifteen minutes`() {
        val t = LoginThrottle()
        repeat(5) { t.onFailure("a", nowMs = 1000L + it) }
        assertTrue(t.lockedForMs("a", nowMs = 2000) > 14 * 60_000)
        assertEquals(0L, t.lockedForMs("b", nowMs = 2000))
        assertEquals(0L, t.lockedForMs("a", nowMs = 2000 + LoginThrottle.CLIENT_LOCK_MS))
    }

    @Test
    fun `success clears the client's failures`() {
        val t = LoginThrottle()
        repeat(4) { t.onFailure("a", nowMs = 0) }
        t.onSuccess("a")
        t.onFailure("a", nowMs = 1)
        assertEquals(0L, t.lockedForMs("a", nowMs = 2))
    }

    @Test
    fun `many failures from different clients lock everyone`() {
        val t = LoginThrottle()
        repeat(LoginThrottle.GLOBAL_MAX_FAILURES) { t.onFailure("ip$it", nowMs = 1000) }
        assertTrue(t.lockedForMs("fresh", nowMs = 1001) > 0)
    }

    @Test
    fun `old failures age out of the global window`() {
        val t = LoginThrottle()
        repeat(LoginThrottle.GLOBAL_MAX_FAILURES - 1) { t.onFailure("ip$it", nowMs = 0) }
        t.onFailure("late", nowMs = LoginThrottle.GLOBAL_WINDOW_MS + 1)
        assertEquals(0L, t.lockedForMs("fresh", nowMs = LoginThrottle.GLOBAL_WINDOW_MS + 2))
    }
}
