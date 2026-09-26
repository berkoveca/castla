package com.castla.mirror.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankStreamDetectorTest {

    private val black = 1_500      // keyframe of an all-black 704x1360 picture
    private val real = 40_000      // keyframe of a real app screen

    /** Feeds keyframes every [stepMs] and returns true if any call fired. */
    private fun BlankStreamDetector.feed(size: Int, fromMs: Long, toMs: Long, stepMs: Long = 600, appShown: Boolean = true): Boolean {
        var fired = false
        var t = fromMs
        while (t <= toMs) { if (onKeyFrame(size, t, appShown)) fired = true; t += stepMs }
        return fired
    }

    @Test
    fun `black keyframes for long enough while an app is shown fire`() {
        val d = BlankStreamDetector()
        assertTrue(d.feed(black, 0, 7_000))
    }

    @Test
    fun `short black moment (splash, transition) does not fire`() {
        val d = BlankStreamDetector()
        assertFalse(d.feed(black, 0, 4_000))
        assertFalse(d.onKeyFrame(real, 4_500, true))
        assertFalse(d.feed(black, 5_000, 9_000))
    }

    @Test
    fun `real picture never fires`() {
        assertFalse(BlankStreamDetector().feed(real, 0, 60_000))
    }

    @Test
    fun `home screen is not checked`() {
        assertFalse(BlankStreamDetector().feed(black, 0, 60_000, appShown = false))
    }

    @Test
    fun `fires once, then waits for the cooldown`() {
        val d = BlankStreamDetector()
        assertTrue(d.feed(black, 0, 7_000))
        assertFalse(d.feed(black, 7_600, 30_000))
        assertTrue(d.feed(black, 30_600, 60_000))
    }

    @Test
    fun `too few keyframes are not enough evidence`() {
        val d = BlankStreamDetector()
        assertFalse(d.onKeyFrame(black, 0, true))
        assertFalse(d.onKeyFrame(black, 10_000, true))
    }
}
