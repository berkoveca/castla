package com.castla.mirror.utils

import kotlin.math.sqrt

object StreamMath {
    /** Hard ceiling on encoded stream width (matches Tesla 1920×1200 display). */
    const val MJPEG_MAX_WIDTH = 1920
    /** Decode budget for smooth MJPEG (~1600×720) — matches MCU2's ~1.2 MP sweet spot. */
    const val MJPEG_SMOOTH_BUDGET_PIXELS = 1_200_000L
    /** Decode budget for OTT/video MJPEG (~1920×864, full display width on MCU2). */
    const val MJPEG_VIDEO_BUDGET_PIXELS = 1_660_000L
    /** JPEG encode quality for MJPEG streams (75: crisp text, modest bytes). */
    const val MJPEG_QUALITY = 75
    /** Encode FPS ceiling for MJPEG — MCU2 decode is the bottleneck, not the encoder. */
    const val MJPEG_MAX_FPS = 20

    /**
     * Squares a resolution down (never up) preserving the aspect ratio,
     * so both dimensions fit within the given caps.
     */
    fun capResolutionPreservingAspect(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        return (width * scale).toInt() to (height * scale).toInt()
    }

    /**
     * Caps a resolution to a decode pixel budget while preserving aspect ratio.
     * Used by the MJPEG path (e.g. Tesla MCU2) where client-side JPEG decode
     * cost dominates — encode at what the browser can actually consume.
     */
    fun capToDecodeBudget(width: Int, height: Int, maxPixels: Long, maxWidth: Int = MJPEG_MAX_WIDTH): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxPixels > 0)
        var w = width.toDouble()
        var h = height.toDouble()
        if (w > maxWidth) {
            val s = maxWidth.toDouble() / w
            w *= s
            h *= s
        }
        if (w * h > maxPixels) {
            val s = sqrt(maxPixels.toDouble() / (w * h))
            w *= s
            h *= s
        }
        return w.toInt() to h.toInt()
    }

    /** Decode budget for MJPEG depending on whether OTT video is playing. */
    fun mjpegBudgetFor(isVideoApp: Boolean): Long =
        if (isVideoApp) MJPEG_VIDEO_BUDGET_PIXELS else MJPEG_SMOOTH_BUDGET_PIXELS

    /** MJPEG encode FPS derived from a tier FPS, capped at the MCU2 decode ceiling. */
    fun mjpegFpsForTier(tierFps: Int): Int = tierFps.coerceIn(1, MJPEG_MAX_FPS)

    /**
     * Minimum H.264 level (as MediaCodecInfo.CodecProfileLevel.AVCLevel*)
     * whose per-frame macroblock limit supports the given resolution.
     * 1920x1080 = 8160 MB -> Level 4 (0x800); 1920x1200 = 9000 MB -> Level 5 (0x4000).
     */
    fun avcLevelFor(width: Int, height: Int): Int {
        val mbCols = (width + 15) / 16
        val mbRows = (height + 15) / 16
        val mbs = mbCols * mbRows.toLong()
        return when {
            mbs <= 99 -> 0x001 // AVCLevel1
            mbs <= 396 -> 0x020 // AVCLevel2
            mbs <= 792 -> 0x040 // AVCLevel21
            mbs <= 1620 -> 0x100 // AVCLevel3
            mbs <= 3600 -> 0x200 // AVCLevel31 (720p)
            mbs <= 5120 -> 0x400 // AVCLevel32
            mbs <= 8192 -> 0x800 // AVCLevel4 (1080p)
            mbs <= 8704 -> 0x2000 // AVCLevel42
            mbs <= 22080 -> 0x4000 // AVCLevel5 (1920x1200)
            mbs <= 36864 -> 0x8000 // AVCLevel51
            else -> 0x10000 // AVCLevel52
        }
    }
    /**
     * Calculates target bitrate based on pixel count relative to 720p base (4Mbps).
     * @param width The target width
     * @param height The target height
     */
    fun calculateBaseBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((4_000_000L * pixels) / basePixels).toInt().coerceIn(1_000_000, 15_000_000)
    }

    /**
     * Calculates bitrate for secondary/split-screen streams.
     * Uses a lower base (3Mbps) and tighter ceiling since secondary
     * content shares bandwidth with the primary stream.
     */
    fun calculateSecondaryBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((3_000_000L * pixels) / basePixels).toInt().coerceIn(750_000, 10_000_000)
    }

    /**
     * Bitrate for the video pane in split mode — boosted 1.5x over secondary base
     * to prioritize video quality. Capped at 12Mbps to leave headroom for the companion pane.
     */
    fun calculateSplitVideoBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((3_000_000L * pixels * 15) / (basePixels * 10)).toInt().coerceIn(750_000, 12_000_000)
    }

    /**
     * Bitrate for the non-video companion pane in split mode — reduced to 0.6x of secondary base.
     * Frees bandwidth for the video pane while keeping the companion usable.
     */
    fun calculateSplitCompanionBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        val basePixels = 1280L * 720
        return ((3_000_000L * pixels * 6) / (basePixels * 10)).toInt().coerceIn(500_000, 6_000_000)
    }

    /**
     * Applies OTT/video-app bitrate boost (1.2x, capped at 15Mbps).
     * Only call when thermal status is NONE (no throttling).
     */
    fun calculateOttBitrate(baseBitrate: Int): Int {
        return minOf((baseBitrate * 1.2).toInt(), 15_000_000)
    }

    /**
     * Computes the DPI for a virtual display so content remains scaled comfortably.
     */
    fun calculateDpi(height: Int): Int {
        return (height * 240 / 720).coerceIn(120, 320)
    }

    /** Default display density scale (Small). */
    const val DENSITY_SCALE_DEFAULT = 0.7f

    /** All supported density scale values, from largest (original) to most compact. */
    val DENSITY_SCALE_OPTIONS = listOf(1.0f, 0.85f, 0.7f, 0.55f)

    /**
     * Applies a display density scale to the base DPI.
     * @param baseDpi DPI computed by [calculateDpi]
     * @param scale density scale factor (1.0 = large, 0.85 = default, 0.7/0.55 = compact)
     * @return scaled DPI, clamped to [100, 320]
     */
    fun applyDensityScale(baseDpi: Int, scale: Float): Int {
        return (baseDpi * scale).toInt().coerceIn(100, 320)
    }
}
