package com.castla.mirror.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSizingPolicyTest {

    private fun size(w: Int, h: Int, dpr: Float, maxShort: Int, maxPixels: Long = StreamSizingPolicy.MAX_PIXELS) =
        StreamSizingPolicy.targetSize(w, h, dpr, maxShort, maxPixels)

    @Test
    fun `dpr 1 viewport inside the caps is unchanged`() {
        assertEquals(1280 to 720, size(1280, 720, 1f, 720))
    }

    @Test
    fun `landscape MCU2 viewport at dpr 1_53 is encoded near physical resolution`() {
        // Tesla MCU2 browser after the 2026 DPR change: 1254x784 CSS px on a 1920x1200 panel.
        val (w, h) = size(1254, 784, 1.53f, 960)
        assertEquals(960, h)
        assertTrue("w=$w", w in 1530..1540)
    }

    @Test
    fun `portrait viewport is capped by its short side, not its height`() {
        // Model S portrait browser: capping the HEIGHT to 720 used to give ~513x720.
        val (w, h) = size(784, 1100, 1.53f, 720)
        assertEquals(720, w)
        assertTrue("h=$h", h in 1005..1015)
    }

    @Test
    fun `pixel budget caps large viewports while keeping aspect`() {
        val (w, h) = size(2560, 1440, 1f, 1440, maxPixels = 1_500_000)
        assertTrue("pixels=${w.toLong() * h}", w.toLong() * h <= 1_500_000)
        assertEquals(2560.0 / 1440, w.toDouble() / h, 0.01)
    }

    @Test
    fun `dpr is clamped to the maximum`() {
        assertEquals(size(1000, 600, StreamSizingPolicy.MAX_DPR, 5000, 100_000_000),
            size(1000, 600, 10f, 5000, 100_000_000))
    }

    @Test
    fun `invalid or sub-1 dpr is treated as 1`() {
        assertEquals(1000 to 600, size(1000, 600, 0.5f, 5000))
        assertEquals(1000 to 600, size(1000, 600, Float.NaN, 5000))
        assertEquals(1000 to 600, size(1000, 600, 0f, 5000))
    }

    @Test
    fun `never exceeds the short-side cap`() {
        for (dpr in listOf(1f, 1.25f, 1.53f, 2f)) {
            val (w, h) = size(1920, 1080, dpr, 800)
            assertTrue("dpr=$dpr ${w}x$h", minOf(w, h) <= 800)
        }
    }
}
