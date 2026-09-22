package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

class EncoderRecoveryPolicyTest {

    @Test
    fun `first errors request a rebuild`() {
        assertTrue(EncoderRecoveryPolicy.shouldRebuild(1))
        assertTrue(EncoderRecoveryPolicy.shouldRebuild(2))
        assertTrue(EncoderRecoveryPolicy.shouldRebuild(3))
    }

    @Test
    fun `too many consecutive errors stop the session instead of looping`() {
        assertFalse(EncoderRecoveryPolicy.shouldRebuild(4))
        assertTrue(EncoderRecoveryPolicy.shouldStopSession(4))
        assertFalse(EncoderRecoveryPolicy.shouldStopSession(1))
    }

    @Test
    fun `rebuild delay backs off`() {
        val first = EncoderRecoveryPolicy.rebuildDelayMs(1)
        val second = EncoderRecoveryPolicy.rebuildDelayMs(2)
        val third = EncoderRecoveryPolicy.rebuildDelayMs(3)
        assertTrue(first > 0)
        assertTrue(second > first)
        assertTrue(third > second)
        assertTrue(third <= EncoderRecoveryPolicy.MAX_REBUILD_DELAY_MS)
    }

    @Test
    fun `zero or negative consecutive counts still rebuild with base delay`() {
        assertTrue(EncoderRecoveryPolicy.shouldRebuild(0))
        assertEquals(EncoderRecoveryPolicy.BASE_REBUILD_DELAY_MS, EncoderRecoveryPolicy.rebuildDelayMs(0))
        assertEquals(EncoderRecoveryPolicy.BASE_REBUILD_DELAY_MS, EncoderRecoveryPolicy.rebuildDelayMs(-1))
    }
}
