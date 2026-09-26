package com.castla.mirror.policy

/**
 * Detects a virtual display that went black while an app is on it.
 *
 * Seen in the field: the stream kept running at full frame rate, but every
 * keyframe was ~1.5 KB (a keyframe of a real app screen is tens of KB), i.e.
 * the display composed a solid black picture — and stayed so for every app
 * launched on it, until the display was recreated (split screen did that).
 * Keyframe size is the cheap signal: a black/solid picture compresses to
 * almost nothing, any real UI does not.
 */
class BlankStreamDetector(
    private val maxBlankKeyFrameBytes: Int = 4_000,
    private val blankForMs: Long = 6_000,
    private val minKeyFrames: Int = 4,
    private val cooldownMs: Long = 30_000
) {
    private var blankSinceMs = -1L
    private var blankKeyFrames = 0
    private var lastFiredMs = Long.MIN_VALUE / 2

    /** Returns true when the display should be treated as stuck black. */
    @Synchronized
    fun onKeyFrame(sizeBytes: Int, nowMs: Long, appShown: Boolean): Boolean {
        if (!appShown || sizeBytes > maxBlankKeyFrameBytes) {
            reset()
            return false
        }
        if (blankSinceMs < 0) blankSinceMs = nowMs
        blankKeyFrames++
        if (blankKeyFrames >= minKeyFrames &&
            nowMs - blankSinceMs >= blankForMs &&
            nowMs - lastFiredMs >= cooldownMs
        ) {
            lastFiredMs = nowMs
            reset()
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        blankSinceMs = -1L
        blankKeyFrames = 0
    }
}
