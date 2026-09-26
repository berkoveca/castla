package com.castla.mirror.policy

import com.castla.mirror.policy.ScreenTimeoutPolicy.FALLBACK_RESTORE_MS
import com.castla.mirror.policy.ScreenTimeoutPolicy.KEEP_AWAKE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeoutPolicyTest {

    @Test
    fun `parses settings output`() {
        assertEquals(30_000L, ScreenTimeoutPolicy.parse("30000\n"))
        assertNull(ScreenTimeoutPolicy.parse("null"))
        assertNull(ScreenTimeoutPolicy.parse(""))
        assertNull(ScreenTimeoutPolicy.parse(null))
    }

    @Test
    fun `saves the user's own timeout at session start`() {
        assertEquals(30_000L, ScreenTimeoutPolicy.valueToSave(30_000L, null))
    }

    @Test
    fun `never saves our own keep-awake value as the user's`() {
        assertNull(ScreenTimeoutPolicy.valueToSave(KEEP_AWAKE_MS, null))
    }

    @Test
    fun `keeps the value saved before a crash`() {
        assertNull(ScreenTimeoutPolicy.valueToSave(KEEP_AWAKE_MS, 30_000L))
    }

    @Test
    fun `restores the saved value`() {
        assertEquals(30_000L, ScreenTimeoutPolicy.valueToRestore(30_000L, KEEP_AWAKE_MS))
    }

    @Test
    fun `nothing to do when already restored`() {
        assertNull(ScreenTimeoutPolicy.valueToRestore(30_000L, 30_000L))
        assertNull(ScreenTimeoutPolicy.valueToRestore(null, 30_000L))
    }

    @Test
    fun `our value with nothing saved falls back to one minute`() {
        assertEquals(FALLBACK_RESTORE_MS, ScreenTimeoutPolicy.valueToRestore(null, KEEP_AWAKE_MS))
    }
}
