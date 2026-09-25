package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMortemParserTest {

    private val sample = """
        prop.sys.boot.reason=shutdown,thermal
        prop.persist.sys.boot.reason.history=shutdown,thermal,1790000000 reboot,userrequested,1789000000
        propgrep=[ro.boot.bootreason]: [reboot]
        setting.phantom_monitor=null
        setting.max_phantom=
        uptime=312.55
        dropbox: 2026-09-25 09:58:01 system_server_watchdog (text, 51234 bytes)
        dropbox: 2026-09-25 09:58:40 SYSTEM_RESTART (text, 120 bytes)
        detail.system_server_watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on display thread
        tomb: Cmdline: /system/bin/surfaceflinger
        kmsg: [ 1234.5] thermal: critical temperature reached, shutting down
        garbage line without prefix
    """.trimIndent()

    @Test
    fun `parses properties settings and events`() {
        val r = PostMortemParser.parse(sample)
        assertEquals("shutdown,thermal", r.props["sys.boot.reason"])
        assertEquals("null", r.settings["phantom_monitor"])
        assertEquals("", r.settings["max_phantom"])
        assertEquals("312.55", r.uptimeSec)
        assertEquals(2, r.dropboxEvents.size)
        assertTrue(r.dropboxEvents[0].contains("system_server_watchdog"))
        assertEquals(1, r.propGrep.size)
        assertEquals(1, r.details.size)
        assertEquals(1, r.tombstones.size)
        assertEquals(1, r.kmsg.size)
    }

    @Test
    fun `native crash abort message thread and backtrace are shown in their own section`() {
        val r = PostMortemParser.parse(
            """
            native: system_server_native_crash: pid: 1234, tid: 1400, name: InputDispatcher  >>> system_server <<<
            native: system_server_native_crash: Abort message: 'Check failed: ...'
            native: system_server_native_crash:       #00 pc 000000000004f8a4  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)
            """.trimIndent()
        )
        assertEquals(3, r.nativeCrash.size)
        val lines = r.summaryLines()
        val header = lines.indexOf("latest native crash (which thread aborted and why):")
        assertTrue(lines.toString(), header >= 0)
        assertTrue(lines[header + 1].contains("name: InputDispatcher"))
        assertTrue(lines.any { it.contains("#00 pc") })
    }

    @Test
    fun `logcat crash buffer and filtered system log get their own sections`() {
        val r = PostMortemParser.parse(
            """
            crashlog: 09-26 01:56:49.100 F/libc    ( 1234): Fatal signal 6 (SIGABRT) in tid 1400 (InputDispatcher), pid 1234 (system_server)
            crashlog: 09-26 01:56:49.300 F/DEBUG   (20000): Abort message: 'something failed'
            syslog: 09-26 01:56:40.000 W/Watchdog( 1234): Blocked in handler on display thread
            """.trimIndent()
        )
        assertEquals(2, r.crashLog.size)
        assertEquals(1, r.systemLog.size)
        val lines = r.summaryLines()
        assertTrue(lines.contains("Android crash log (logcat -b crash, survives soft restarts):"))
        assertTrue(lines.contains("system log warnings before the restart (filtered):"))
        assertTrue(lines.any { it.contains("Fatal signal 6") })
    }

    @Test
    fun `summary lines include the classified boot reason and the evidence`() {
        val lines = PostMortemParser.parse(sample).summaryLines()
        val joined = lines.joinToString("\n")
        assertTrue(joined, joined.contains("THERMAL"))
        assertTrue(joined, joined.contains("system_server_watchdog"))
        assertTrue(joined, joined.contains("surfaceflinger"))
        assertTrue(joined, joined.contains("critical temperature"))
        assertTrue(joined, joined.contains("phantom"))
    }

    @Test
    fun `empty output yields an explicit empty report`() {
        val r = PostMortemParser.parse("")
        assertTrue(r.dropboxEvents.isEmpty())
        assertTrue(r.summaryLines().isNotEmpty())
    }

    @Test
    fun `boot reason classification`() {
        assertTrue(PostMortemParser.classifyBootReason("shutdown,thermal").startsWith("THERMAL"))
        assertTrue(PostMortemParser.classifyBootReason("reboot,thermal,battery").startsWith("THERMAL"))
        assertTrue(PostMortemParser.classifyBootReason("shutdown,battery").startsWith("BATTERY"))
        assertTrue(PostMortemParser.classifyBootReason("kernel_panic").startsWith("KERNEL_PANIC"))
        assertTrue(PostMortemParser.classifyBootReason("watchdog").startsWith("WATCHDOG"))
        assertTrue(PostMortemParser.classifyBootReason("hard,hw_reset").startsWith("HARDWARE_RESET"))
        assertTrue(PostMortemParser.classifyBootReason("reboot,userrequested").startsWith("USER"))
        assertTrue(PostMortemParser.classifyBootReason("cold,powerkey").startsWith("USER"))
        assertTrue(PostMortemParser.classifyBootReason("").startsWith("UNKNOWN"))
        assertTrue(PostMortemParser.classifyBootReason("reboot").startsWith("UNCLASSIFIED"))
    }
}
