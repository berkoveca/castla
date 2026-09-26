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
    private val blankForMs: Long = 8_000,
    private val minKeyFrames: Int = 4,
    private val cooldownMs: Long = 30_000
) {
    private var blankSinceMs = -1L
    private var blankKeyFrames = 0
    private var lastFiredMs = Long.MIN_VALUE / 2
    /** After a recovery, stay quiet until a real picture was seen (or a new launch re-arms):
     *  an app whose own screen is black (loading) must not be relaunched in a loop. */
    private var armed = true

    /** Returns true when the display should be treated as stuck black. */
    @Synchronized
    fun onKeyFrame(sizeBytes: Int, nowMs: Long, appShown: Boolean): Boolean {
        if (!appShown) {
            reset()
            return false
        }
        if (sizeBytes > maxBlankKeyFrameBytes) {
            reset()
            armed = true
            return false
        }
        if (!armed) return false
        if (blankSinceMs < 0) blankSinceMs = nowMs
        blankKeyFrames++
        if (blankKeyFrames >= minKeyFrames &&
            nowMs - blankSinceMs >= blankForMs &&
            nowMs - lastFiredMs >= cooldownMs
        ) {
            lastFiredMs = nowMs
            armed = false
            reset()
            return true
        }
        return false
    }

    /** New app launched / display recreated: start over and allow one recovery again. */
    @Synchronized
    fun rearm() {
        reset()
        armed = true
    }

    /** Forget the current blank run (e.g. the display was just rebuilt). */
    @Synchronized
    fun reset() {
        blankSinceMs = -1L
        blankKeyFrames = 0
    }
}
