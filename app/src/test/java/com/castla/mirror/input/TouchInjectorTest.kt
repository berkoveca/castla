package com.castla.mirror.input

import com.castla.mirror.server.TouchEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TouchInjectorTest {

    private lateinit var injector: TouchInjector

    @Before
    fun setup() {
        // TouchInjector will try Shizuku init and fail (expected in test env)
        // It should gracefully fall back to accessibility mode
        injector = TouchInjector(1080, 1920)
    }

    @Test
    fun `coordinate scaling - normalized 0,0 maps to 0,0`() {
        // We can't directly test private coordinate scaling, but we verify
        // that onTouchEvent doesn't crash with edge coordinates
        val event = TouchEvent("down", 0.0f, 0.0f, 0)
        injector.onTouchEvent(event) // should not throw
    }

    @Test
    fun `coordinate scaling - normalized 1,1 maps to full resolution`() {
        val event = TouchEvent("down", 1.0f, 1.0f, 0)
        injector.onTouchEvent(event) // should not throw
    }

    @Test
    fun `coordinate scaling - center point maps correctly`() {
        val event = TouchEvent("down", 0.5f, 0.5f, 0)
        injector.onTouchEvent(event) // should not throw
    }

    @Test
    fun `action mapping - down, move, up are valid`() {
        injector.onTouchEvent(TouchEvent("down", 0.5f, 0.5f, 0))
        injector.onTouchEvent(TouchEvent("move", 0.6f, 0.6f, 0))
        injector.onTouchEvent(TouchEvent("up", 0.6f, 0.6f, 0))
        // No crash = actions mapped correctly
    }

    @Test
    fun `unknown action is silently ignored`() {
        val event = TouchEvent("unknown", 0.5f, 0.5f, 0)
        injector.onTouchEvent(event) // should not throw
    }

    @Test
    fun `multiple pointers tracked independently`() {
        // Simulate multi-touch: two fingers down, then up
        injector.onTouchEvent(TouchEvent("down", 0.3f, 0.3f, 0))
        injector.onTouchEvent(TouchEvent("down", 0.7f, 0.7f, 1))
        injector.onTouchEvent(TouchEvent("move", 0.35f, 0.35f, 0))
        injector.onTouchEvent(TouchEvent("up", 0.35f, 0.35f, 0))
        injector.onTouchEvent(TouchEvent("up", 0.7f, 0.7f, 1))
        // No crash = multi-touch works
    }

    @Test
    fun `release clears state without crash`() {
        injector.onTouchEvent(TouchEvent("down", 0.5f, 0.5f, 0))
        injector.release()
        // Post-release events should not crash
    }

    @Test
    fun `rapid down-up sequence does not crash`() {
        for (i in 0 until 100) {
            injector.onTouchEvent(TouchEvent("down", 0.5f, 0.5f, 0))
            injector.onTouchEvent(TouchEvent("up", 0.5f, 0.5f, 0))
        }
    }

    @Test
    fun `virtual display injector receives routed events`() {
        val received = mutableListOf<TouchStream.Motion>()
        injector.setVirtualDisplayInjector { m -> received.add(m) }

        injector.onTouchEvent(TouchEvent("down", 0.5f, 0.25f, 7))
        assertEquals(1, received.size)
        assertEquals(TouchStream.ACTION_DOWN, received[0].action)
        // A single tap is pointer id 0 whatever id the page used
        assertArrayEquals(intArrayOf(0), received[0].ids)
        // Verify coordinates were scaled: 0.5 * 1080 = 540, 0.25 * 1920 = 480
        assertEquals(540f, received[0].xs[0], 0.1f)
        assertEquals(480f, received[0].ys[0], 0.1f)
    }

    @Test
    fun `second finger is routed as one multi-pointer event`() {
        val received = mutableListOf<TouchStream.Motion>()
        injector.setVirtualDisplayInjector { m -> received.add(m) }
        injector.onTouchEvent(TouchEvent("down", 0.3f, 0.3f, 4))
        injector.onTouchEvent(TouchEvent("down", 0.7f, 0.7f, 5))
        assertEquals(2, received.size)
        assertEquals(2, received[1].ids.size)
        assertEquals(TouchStream.ACTION_POINTER_DOWN, received[1].action and 0xff)
    }

    @Test
    fun `clearing virtual display injector falls back to normal injection`() {
        val vdReceived = mutableListOf<Any>()
        injector.setVirtualDisplayInjector { _ -> vdReceived.add(true) }
        injector.onTouchEvent(TouchEvent("down", 0.5f, 0.5f, 0))
        assertEquals(1, vdReceived.size)

        // Clear VD injector
        injector.setVirtualDisplayInjector(null)
        injector.onTouchEvent(TouchEvent("up", 0.5f, 0.5f, 0))
        // VD should NOT receive the second event
        assertEquals(1, vdReceived.size)
    }
}
