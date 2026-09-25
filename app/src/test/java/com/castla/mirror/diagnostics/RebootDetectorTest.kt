package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebootDetectorTest {

    private val t0 = 1_780_000_000_000L

    private fun crumb(
        bootCount: Int? = 10,
        bootWallMs: Long = t0,
        pid: Int = 111,
        sessionActive: Boolean = false
    ) = Breadcrumb(BootState(bootCount, bootWallMs), pid, sessionActive, lastHeartbeatWallMs = null, lastHeartbeat = null)

    @Test
    fun `same boot when boot counts match`() {
        assertTrue(RebootDetector.isSameBoot(BootState(10, t0), BootState(10, t0 + 10 * 60_000)))
    }

    @Test
    fun `different boot when boot counts differ even if wall time matches`() {
        assertFalse(RebootDetector.isSameBoot(BootState(10, t0), BootState(11, t0)))
    }

    @Test
    fun `falls back to boot wall time when boot count is unavailable`() {
        assertTrue(RebootDetector.isSameBoot(BootState(null, t0), BootState(null, t0 + 30_000)))
        assertFalse(RebootDetector.isSameBoot(BootState(null, t0), BootState(null, t0 + 3_600_000)))
        assertTrue(RebootDetector.isSameBoot(BootState(10, t0), BootState(null, t0 - 5_000)))
    }

    @Test
    fun `no breadcrumb means first run`() {
        assertEquals(RebootVerdict.FIRST_RUN, RebootDetector.evaluate(null, BootState(10, t0), currentPid = 5))
    }

    @Test
    fun `same boot and no session is a normal restart`() {
        assertEquals(RebootVerdict.SAME_BOOT,
            RebootDetector.evaluate(crumb(), BootState(10, t0), currentPid = 222))
    }

    @Test
    fun `new boot without session is a plain reboot`() {
        assertEquals(RebootVerdict.REBOOTED,
            RebootDetector.evaluate(crumb(sessionActive = false), BootState(11, t0 + 600_000), currentPid = 222))
    }

    @Test
    fun `new boot with an active session is flagged`() {
        assertEquals(RebootVerdict.REBOOTED_DURING_SESSION,
            RebootDetector.evaluate(crumb(sessionActive = true), BootState(11, t0 + 600_000), currentPid = 222))
    }

    @Test
    fun `same boot but the previous process never ended its session means the app process died`() {
        assertEquals(RebootVerdict.PROCESS_DIED_DURING_SESSION,
            RebootDetector.evaluate(crumb(sessionActive = true, pid = 111), BootState(10, t0), currentPid = 222))
    }

    @Test
    fun `same pid and active session is not treated as a process death`() {
        assertEquals(RebootVerdict.SAME_BOOT,
            RebootDetector.evaluate(crumb(sessionActive = true, pid = 111), BootState(10, t0), currentPid = 111))
    }
}
