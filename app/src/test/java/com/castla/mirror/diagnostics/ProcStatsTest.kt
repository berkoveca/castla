package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatsTest {

    @Test
    fun `load average is the first three fields`() {
        assertEquals("3.12/2.50/1.98", ProcStats.loadAvg("3.12 2.50 1.98 4/1532 28811\n"))
        assertNull(ProcStats.loadAvg(""))
    }

    @Test
    fun `thread count from status`() {
        assertEquals(87, ProcStats.threads("Name:\tcastla\nThreads:\t87\nVmRSS:\t1 kB\n"))
        assertNull(ProcStats.threads("Name:\tx\n"))
    }

    @Test
    fun `cpu ticks are utime plus stime, even when the name has spaces`() {
        // fields after ')' : state=3 ... utime=14 stime=15 (1-based in proc(5))
        val stat = "1234 (com.castla (x)) S 1 2 3 4 5 6 7 8 9 10 500 250 0 0 20 0 60 0"
        assertEquals(750L, ProcStats.cpuTicks(stat))
        assertNull(ProcStats.cpuTicks("garbage"))
    }

    @Test
    fun `cpu percent of one core over the interval`() {
        // 150 ticks at 100 Hz = 1.5 s of CPU in 3 s wall = 50 % of one core
        assertEquals(50, ProcStats.cpuPercent(prevTicks = 1000, nowTicks = 1150, elapsedMs = 3000))
        assertEquals(0, ProcStats.cpuPercent(prevTicks = 1000, nowTicks = 1000, elapsedMs = 0))
    }
}
