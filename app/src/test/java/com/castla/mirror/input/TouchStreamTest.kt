package com.castla.mirror.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchStreamTest {

    private val DOWN = TouchStream.ACTION_DOWN
    private val UP = TouchStream.ACTION_UP
    private val MOVE = TouchStream.ACTION_MOVE
    private val CANCEL = TouchStream.ACTION_CANCEL
    private fun pointerDown(index: Int) = TouchStream.ACTION_POINTER_DOWN or (index shl TouchStream.POINTER_INDEX_SHIFT)
    private fun pointerUp(index: Int) = TouchStream.ACTION_POINTER_UP or (index shl TouchStream.POINTER_INDEX_SHIFT)

    @Test
    fun `single tap uses pointer id 0 whatever id the page sent`() {
        // Field crash: page ids climbed to 17 per tap; real touchscreens restart at 0.
        val s = TouchStream()
        val d = s.onEvent("down", pageId = 17, x = 10f, y = 20f, nowMs = 1000).single()
        assertEquals(DOWN, d.action)
        assertArrayEquals(intArrayOf(0), d.ids)
        val u = s.onEvent("up", pageId = 17, x = 11f, y = 21f, nowMs = 1100).single()
        assertEquals(UP, u.action)
        assertArrayEquals(intArrayOf(0), u.ids)
    }

    @Test
    fun `whole gesture shares the down time`() {
        val s = TouchStream()
        s.onEvent("down", 3, 0f, 0f, nowMs = 1000)
        val m = s.onEvent("move", 3, 5f, 5f, nowMs = 1050).single()
        val u = s.onEvent("up", 3, 5f, 5f, nowMs = 1100).single()
        assertEquals(MOVE, m.action)
        assertEquals(1000L, m.downTime)
        assertEquals(1000L, u.downTime)
    }

    @Test
    fun `second finger is a POINTER_DOWN carrying both pointers`() {
        val s = TouchStream()
        s.onEvent("down", 5, 1f, 1f, nowMs = 0)
        val pd = s.onEvent("down", 6, 9f, 9f, nowMs = 10).single()
        assertEquals(pointerDown(1), pd.action)
        assertArrayEquals(intArrayOf(0, 1), pd.ids)
        assertArrayEquals(floatArrayOf(1f, 9f), pd.xs, 0f)
        val mv = s.onEvent("move", 5, 2f, 2f, nowMs = 20).single()
        assertEquals(MOVE, mv.action)
        assertArrayEquals(floatArrayOf(2f, 9f), mv.xs, 0f)
        val pu = s.onEvent("up", 5, 2f, 2f, nowMs = 30).single()
        assertEquals(pointerUp(0), pu.action)
        assertArrayEquals(intArrayOf(0, 1), pu.ids)
        val u = s.onEvent("up", 6, 9f, 9f, nowMs = 40).single()
        assertEquals(UP, u.action)
        assertArrayEquals(intArrayOf(1), u.ids)
    }

    @Test
    fun `moves and ups for unknown pointers are dropped, not synthesized`() {
        val s = TouchStream()
        assertTrue(s.onEvent("move", 1, 0f, 0f, nowMs = 0).isEmpty())
        assertTrue(s.onEvent("up", 1, 0f, 0f, nowMs = 0).isEmpty())
    }

    @Test
    fun `repeated down for an active pointer is a move`() {
        val s = TouchStream()
        s.onEvent("down", 1, 0f, 0f, nowMs = 0)
        assertEquals(MOVE, s.onEvent("down", 1, 3f, 3f, nowMs = 10).single().action)
    }

    @Test
    fun `a down after a lost up cancels the stale gesture first`() {
        val s = TouchStream()
        s.onEvent("down", 1, 0f, 0f, nowMs = 0)
        val out = s.onEvent("down", 2, 5f, 5f, nowMs = TouchStream.STALE_GESTURE_MS + 1)
        assertEquals(2, out.size)
        assertEquals(CANCEL, out[0].action)
        assertEquals(DOWN, out[1].action)
        assertArrayEquals(intArrayOf(0), out[1].ids)
    }

    @Test
    fun `reset cancels an open gesture`() {
        val s = TouchStream()
        s.onEvent("down", 1, 0f, 0f, nowMs = 0)
        assertEquals(CANCEL, s.reset()!!.action)
        assertEquals(null, s.reset())
    }

    @Test
    fun `ids are the lowest free and at most ten pointers`() {
        val s = TouchStream()
        for (i in 0 until 12) s.onEvent("down", 100 + i, 0f, 0f, nowMs = i.toLong())
        assertEquals(10, s.activeCount)
    }
}
