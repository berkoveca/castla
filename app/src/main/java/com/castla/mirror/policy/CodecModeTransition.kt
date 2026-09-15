package com.castla.mirror.policy

/**
 * Pure decision for whether a client codec-mode request should trigger a
 * pipeline rebuild.
 *
 * Encapsulates the guard used by `MirrorForegroundService.onCodecModeRequest`
 * so it can be unit-tested without spinning up an Android Service. Keeps the
 * orchestration (mutex, encoder tear-down, VD swap) in the service while the
 * branching logic lives here.
 */
object CodecModeTransition {

    const val MODE_H264 = "h264"
    const val MODE_MJPEG = "mjpeg"
    const val MODE_FMP4 = "fmp4"

    /**
     * @param requestedMode mode string carried by the client control message
     * @param currentMode the service's currently active codec mode
     * @param jpegEncoderActive whether the pipeline for the *requested* mode is already live
     *                          (JpegEncoder for mjpeg, Fmp4Muxer for fmp4)
     * @return true if the service should apply the switch (set mode + rebuild)
     */
    fun shouldApply(
        requestedMode: String,
        currentMode: String,
        jpegEncoderActive: Boolean
    ): Boolean {
        val switchable = requestedMode == MODE_MJPEG || requestedMode == MODE_FMP4
        if (!switchable) return false
        if (requestedMode == currentMode) return !jpegEncoderActive
        return true
    }
}
