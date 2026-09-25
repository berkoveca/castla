package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitDecoderTest {

    @Test
    fun `signalOf decodes 128 plus signal convention`() {
        assertEquals(9, ProcessExitDecoder.signalOf(137))
        assertEquals(15, ProcessExitDecoder.signalOf(143))
        assertEquals(31, ProcessExitDecoder.signalOf(159))
    }

    @Test
    fun `signalOf returns null for plain exit codes`() {
        assertNull(ProcessExitDecoder.signalOf(0))
        assertNull(ProcessExitDecoder.signalOf(1))
        assertNull(ProcessExitDecoder.signalOf(128))
        assertNull(ProcessExitDecoder.signalOf(-1))
        assertNull(ProcessExitDecoder.signalOf(300))
    }

    @Test
    fun `SIGKILL points at the Android process killers`() {
        val d = ProcessExitDecoder.describe(137)
        assertTrue(d, d.contains("exit=137"))
        assertTrue(d, d.contains("SIGKILL"))
        assertTrue(d, d.contains("killed"))
    }

    @Test
    fun `SIGTERM is described as a stop request`() {
        val d = ProcessExitDecoder.describe(143)
        assertTrue(d, d.contains("SIGTERM"))
    }

    @Test
    fun `SIGSYS mentions seccomp`() {
        val d = ProcessExitDecoder.describe(159)
        assertTrue(d, d.contains("SIGSYS"))
        assertTrue(d, d.contains("seccomp"))
    }

    @Test
    fun `crash signals are named`() {
        assertTrue(ProcessExitDecoder.describe(139).contains("SIGSEGV"))
        assertTrue(ProcessExitDecoder.describe(134).contains("SIGABRT"))
    }

    @Test
    fun `plain exit codes are explained`() {
        assertTrue(ProcessExitDecoder.describe(0).contains("clean"))
        assertTrue(ProcessExitDecoder.describe(1).contains("exit=1"))
        assertTrue(ProcessExitDecoder.describe(2).contains("panic"))
    }

    @Test
    fun `unknown exit code is reported as unknown`() {
        assertTrue(ProcessExitDecoder.describe(-1).contains("unknown"))
    }

    @Test
    fun `unrecognized exit code still includes the raw number`() {
        assertTrue(ProcessExitDecoder.describe(42).contains("42"))
        assertTrue(ProcessExitDecoder.describe(128 + 20).contains("signal 20"))
    }
}
