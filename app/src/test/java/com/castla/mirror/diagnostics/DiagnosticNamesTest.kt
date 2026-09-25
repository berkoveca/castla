package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticNamesTest {

    @Test
    fun `thermal status names`() {
        assertEquals("NONE(0)", DiagnosticNames.thermal(0))
        assertEquals("SEVERE(3)", DiagnosticNames.thermal(3))
        assertEquals("SHUTDOWN(6)", DiagnosticNames.thermal(6))
        assertEquals("UNKNOWN(9)", DiagnosticNames.thermal(9))
    }

    @Test
    fun `exit reason names`() {
        assertEquals("LOW_MEMORY", DiagnosticNames.exitReason(3))
        assertEquals("CRASH_NATIVE", DiagnosticNames.exitReason(5))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", DiagnosticNames.exitReason(9))
        assertEquals("FREEZER", DiagnosticNames.exitReason(14))
        assertEquals("REASON_99", DiagnosticNames.exitReason(99))
    }

    @Test
    fun `process importance names`() {
        assertEquals("FOREGROUND", DiagnosticNames.importance(100))
        assertEquals("FOREGROUND_SERVICE", DiagnosticNames.importance(125))
        assertEquals("CACHED", DiagnosticNames.importance(400))
        assertEquals("IMPORTANCE_777", DiagnosticNames.importance(777))
    }

    @Test
    fun `trim memory level names`() {
        assertEquals("RUNNING_CRITICAL", DiagnosticNames.trimMemory(15))
        assertEquals("UI_HIDDEN", DiagnosticNames.trimMemory(20))
        assertEquals("COMPLETE", DiagnosticNames.trimMemory(80))
        assertEquals("LEVEL_7", DiagnosticNames.trimMemory(7))
    }

    @Test
    fun `parses VmRSS from proc status`() {
        val status = "Name:\tcloudflared\nVmPeak:\t  900000 kB\nVmRSS:\t   61234 kB\nThreads:\t18\n"
        assertEquals(61234L, DiagnosticNames.parseVmRssKb(status))
        assertNull(DiagnosticNames.parseVmRssKb("Name:\tfoo\n"))
    }
}
