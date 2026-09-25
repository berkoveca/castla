package com.castla.mirror.policy

import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the thermal-mitigation policy that keeps the device from reaching a
 * framework thermal SHUTDOWN (instant power-off) or an orphaned-VD thermal REBOOT.
 *
 * PowerManager.THERMAL_STATUS_* integer values (kept inline so the test does not
 * depend on a runtime PowerManager):
 *   NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5
 */
class ThermalMitigationPolicyTest {

    private companion object {
        const val NONE = 0
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4
        const val EMERGENCY = 5
    }

    @Test
    fun `NONE results in no action`() {
        val a = ThermalMitigationPolicy.evaluate(NONE)
        assertEquals(ThermalMitigationPolicy.Action.NONE, a)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `LIGHT only trims bitrate modestly`() {
        val a = ThermalMitigationPolicy.evaluate(LIGHT)
        assertEquals(0.85, a.bitrateFactor!!, 0.0001)
        assertNull(a.fpsOverride)
        assertNull(a.maxHeight)
        assertFalse(a.stopAudio)
        assertFalse(a.stopSecondary)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `MODERATE drops secondary encoder and caps fps`() {
        val a = ThermalMitigationPolicy.evaluate(MODERATE)
        assertEquals(0.6, a.bitrateFactor!!, 0.0001)
        assertEquals(20, a.fpsOverride)
        assertNull(a.maxHeight)
        assertFalse(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `SEVERE stops audio and caps to 720p15`() {
        val a = ThermalMitigationPolicy.evaluate(SEVERE)
        assertEquals(0.4, a.bitrateFactor!!, 0.0001)
        assertEquals(15, a.fpsOverride)
        assertEquals(720, a.maxHeight)
        assertTrue(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `CRITICAL throttles hard but does NOT stop the session`() {
        val a = ThermalMitigationPolicy.evaluate(CRITICAL)
        assertEquals(0.25, a.bitrateFactor!!, 0.0001)
        assertEquals(12, a.fpsOverride)
        assertEquals(480, a.maxHeight)
        assertTrue(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `EMERGENCY requests a clean session stop to protect the device`() {
        val a = ThermalMitigationPolicy.evaluate(EMERGENCY)
        assertTrue(a.emergencyStop)
        assertEquals(0.15, a.bitrateFactor!!, 0.0001)
        assertTrue(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertEquals("emergency", a.label)
    }

    @Test
    fun `headroom at or above 1_0 escalates before the status callback fires`() {
        // getThermalHeadroom: 1.0 == SEVERE forecast, higher is worse
        val a = ThermalMitigationPolicy.evaluate(NONE, 1.05f)
        assertTrue(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertFalse(a.emergencyStop)
        assertEquals(15, a.fpsOverride)
        assertEquals(720, a.maxHeight)
    }

    @Test
    fun `normal headroom on a cool device does not throttle`() {
        // S20 Ultra reports ~0.61 at 28C idle; this used to be read as "severe"
        assertEquals(ThermalMitigationPolicy.Action.NONE, ThermalMitigationPolicy.evaluate(NONE, 0.61f))
        assertEquals(ThermalMitigationPolicy.Action.NONE, ThermalMitigationPolicy.evaluate(NONE, 0.0f))
    }

    @Test
    fun `headroom never triggers an emergency stop on its own`() {
        assertFalse(ThermalMitigationPolicy.evaluate(NONE, 0.0f).emergencyStop)
        assertFalse(ThermalMitigationPolicy.evaluate(NONE, 3.0f).emergencyStop)
    }

    @Test
    fun `headroom in moderate band caps secondary only`() {
        val a = ThermalMitigationPolicy.evaluate(NONE, 0.92f)
        assertEquals(0.6, a.bitrateFactor!!, 0.0001)
        assertFalse(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `NaN headroom is ignored`() {
        val a = ThermalMitigationPolicy.evaluate(LIGHT, Float.NaN)
        assertEquals(0.85, a.bitrateFactor!!, 0.0001)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `merge keeps the most conservative choice across status and headroom`() {
        // MODERATE status but headroom beyond severe => severe limits must win
        val a = ThermalMitigationPolicy.evaluate(MODERATE, 1.2f)
        assertEquals(0.4, a.bitrateFactor!!, 0.0001)
        assertEquals(15, a.fpsOverride)
        assertTrue(a.stopAudio)
        assertTrue(a.stopSecondary)
        assertFalse(a.emergencyStop)
    }

    @Test
    fun `merge combines a status action with a less-severe headroom action without losing the stricter bits`() {
        // SEVERE status + headroom 0.9 (moderate): result should be at least SEVERE
        val a = ThermalMitigationPolicy.evaluate(SEVERE, 0.9f)
        assertEquals(0.4, a.bitrateFactor!!, 0.0001)
        assertEquals(15, a.fpsOverride)
        assertEquals(720, a.maxHeight)
        assertTrue(a.stopAudio)
    }
}
