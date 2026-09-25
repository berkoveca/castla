package com.castla.mirror.policy

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Decides the encode size for a viewport reported by the car's browser.
 *
 * The browser reports CSS pixels. On the Tesla MCU2 browser the device pixel
 * ratio is ~1.53 (since a 2026 update), so a CSS-sized stream gets upscaled
 * 1.5x on the panel and looks soft. We scale by the DPR (clamped), then cap:
 *
 *  - the SHORT side by [maxShortSide] (the "720p/800p/960p" tier). Capping the
 *    height instead crushed portrait viewports (the Model S browser is portrait)
 *    to a narrow, blurry strip.
 *  - total pixels by [maxPixels], which bounds encoder load/heat on the phone and
 *    the bitrate the LTE uplink has to carry.
 *
 * Aspect ratio is preserved; alignment to the codec's macroblock size happens later.
 */
object StreamSizingPolicy {

    const val MAX_DPR = 2.0f
    /** ~1536x960 landscape / ~1024x1440 portrait. */
    const val MAX_PIXELS = 1_500_000L

    fun targetSize(
        requestedWidth: Int,
        requestedHeight: Int,
        dpr: Float,
        maxShortSide: Int,
        maxPixels: Long = MAX_PIXELS
    ): Pair<Int, Int> {
        val scale = if (dpr.isNaN() || dpr < 1f) 1.0 else minOf(dpr, MAX_DPR).toDouble()
        var w = requestedWidth * scale
        var h = requestedHeight * scale

        if (maxShortSide > 0 && minOf(w, h) > maxShortSide) {
            // Set the short side exactly so rounding can't push it over the cap.
            if (w <= h) {
                h = h * maxShortSide / w
                w = maxShortSide.toDouble()
            } else {
                w = w * maxShortSide / h
                h = maxShortSide.toDouble()
            }
        }
        val pixels = w * h
        if (maxPixels > 0 && pixels > maxPixels) {
            // Round down so the result never exceeds the pixel budget.
            val f = sqrt(maxPixels / pixels)
            return floor(w * f).toInt() to floor(h * f).toInt()
        }
        return Math.round(w).toInt() to Math.round(h).toInt()
    }
}
