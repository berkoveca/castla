package com.castla.mirror.diagnostics

import com.castla.mirror.diagnostics.PrivilegedCallTrace.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCallTraceTest {

    @Test
    fun `display lifecycle and launches are risky`() {
        listOf("createVirtualDisplay", "releaseVirtualDisplay", "resizeVirtualDisplay", "setSurface",
            "launchAppOnDisplay", "launchHomeOnDisplay", "setPhysicalDisplayPower",
            "startSystemAudioCapture", "destroy").forEach {
            assertEquals(it, Level.RISKY, PrivilegedCallTrace.levelOf(it, emptyArray()))
        }
    }

    @Test
    fun `shell commands are classified by what they do`() {
        fun lvl(cmd: String) = PrivilegedCallTrace.levelOf("execCommand", arrayOf(cmd))
        assertEquals(Level.RISKY, lvl("am start -W --display 7 -n com.waze/.Main"))
        assertEquals(Level.RISKY, lvl("input -d 7 keyevent 3"))
        assertEquals(Level.RISKY, lvl("cmd activity task resize 12 0 0 800 600"))
        assertEquals(Level.QUIET, lvl("dumpsys input_method | grep mInputShown"))
        assertEquals(Level.QUIET, lvl("getprop sys.boot.reason"))
        assertEquals(Level.NORMAL, lvl("settings put global enable_freeform_support 1"))
    }

    @Test
    fun `polling and touch are not logged per call`() {
        assertEquals(Level.SILENT, PrivilegedCallTrace.levelOf("isAlive", emptyArray()))
        assertEquals(Level.SILENT, PrivilegedCallTrace.levelOf("asBinder", emptyArray()))
        assertEquals(Level.TOUCH, PrivilegedCallTrace.levelOf("injectInput", emptyArray()))
        assertEquals(Level.TOUCH, PrivilegedCallTrace.levelOf("injectMotionEvent", emptyArray()))
    }

    @Test
    fun `typed text is never logged, only its length`() {
        val d = PrivilegedCallTrace.describeCall("injectText", arrayOf("secret password", 7))
        assertFalse(d, d.contains("secret"))
        assertTrue(d, d.contains("<15 chars>"))
    }

    @Test
    fun `long and multi-line commands are shortened`() {
        val d = PrivilegedCallTrace.describeCall("execCommand", arrayOf("p() {\n echo a\n}\n" + "x".repeat(500)))
        assertTrue(d, d.length < 200)
        assertTrue(d, d.contains("+3 lines"))
    }

    @Test
    fun `slow calls are flagged`() {
        assertTrue(PrivilegedCallTrace.isSlow(1500))
        assertFalse(PrivilegedCallTrace.isSlow(200))
    }

    @Test
    fun `touch aggregator logs down and up and summarizes moves`() {
        val agg = PrivilegedCallTrace.TouchAggregator(summaryEveryMs = 5000)
        assertEquals("touch DOWN d=7 id=0 (412,300)", agg.onTouch(7, 0, 412.4f, 300f, 0, nowMs = 1000))
        assertNull(agg.onTouch(7, 2, 420f, 310f, 0, nowMs = 1100))
        assertNull(agg.onTouch(7, 2, 430f, 320f, 0, nowMs = 1200))
        assertEquals("touch UP d=7 id=0 (430,320) moves=2", agg.onTouch(7, 1, 430f, 320f, 0, nowMs = 1300))
    }

    @Test
    fun `second finger and pointer count are shown`() {
        val agg = PrivilegedCallTrace.TouchAggregator(summaryEveryMs = 5000)
        agg.onTouch(7, 0, 1f, 1f, 0, nowMs = 0)
        assertEquals("touch POINTER_DOWN d=7 id=1 (5,5) n=2", agg.onTouch(7, 5 or (1 shl 8), 5f, 5f, 1, nowMs = 10, pointerCount = 2))
    }

    @Test
    fun `long drags get a periodic move summary`() {
        val agg = PrivilegedCallTrace.TouchAggregator(summaryEveryMs = 5000)
        agg.onTouch(7, 0, 0f, 0f, 0, nowMs = 0)
        assertNull(agg.onTouch(7, 2, 1f, 1f, 0, nowMs = 1000))
        assertEquals("touch MOVE d=7 moves=2 in 6000ms", agg.onTouch(7, 2, 2f, 2f, 0, nowMs = 6000))
    }
}
