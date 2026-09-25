package com.castla.mirror.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class HomeKeyGuardTest {

    @Test
    fun `detects HOME keyevent commands and their display`() {
        assertEquals(25, HomeKeyGuard.homeDisplayOf("input -d 25 keyevent 3"))
        assertEquals(7, HomeKeyGuard.homeDisplayOf("input -d 7 keyevent KEYCODE_HOME"))
        assertEquals(7, HomeKeyGuard.homeDisplayOf("input -d 7 keyevent HOME"))
    }

    @Test
    fun `ignores other keys and commands`() {
        assertNull(HomeKeyGuard.homeDisplayOf("input -d 25 keyevent 4"))
        assertNull(HomeKeyGuard.homeDisplayOf("input -d 25 keyevent 37"))
        assertNull(HomeKeyGuard.homeDisplayOf("input keyevent 3"))
        assertNull(HomeKeyGuard.homeDisplayOf("dumpsys activity activities"))
    }

    @Test
    fun `release right after HOME waits out the settle window`() {
        val g = HomeKeyGuard(settleMs = 1000)
        g.onHomeSent(25, nowMs = 10_000)
        assertEquals(1000, g.waitBeforeRelease(25, nowMs = 10_000))
        assertEquals(700, g.waitBeforeRelease(25, nowMs = 10_300))
    }

    @Test
    fun `no wait once settled or for other displays`() {
        val g = HomeKeyGuard(settleMs = 1000)
        g.onHomeSent(25, nowMs = 10_000)
        assertEquals(0, g.waitBeforeRelease(25, nowMs = 11_000))
        assertEquals(0, g.waitBeforeRelease(24, nowMs = 10_000))
    }

    @Test
    fun `HOME only goes to a display that is still alive`() {
        // AOSP 13 startHomeOnDisplay() has no null check: HOME to a released
        // display id crashes system_server (phone soft-reboots).
        assertTrue(HomeKeyGuard.mayPressHome(25, setOf(24, 25)))
        assertFalse(HomeKeyGuard.mayPressHome(25, setOf(26)))
        assertFalse(HomeKeyGuard.mayPressHome(-1, setOf(26)))
    }

    @Test
    fun `default display is always allowed`() {
        assertTrue(HomeKeyGuard.mayPressHome(0, emptySet()))
    }

    @Test
    fun `release forgets the display`() {
        val g = HomeKeyGuard(settleMs = 1000)
        g.onHomeSent(25, nowMs = 10_000)
        g.onReleased(25)
        assertEquals(0, g.waitBeforeRelease(25, nowMs = 10_000))
    }
}
