package com.castla.mirror.policy

/**
 * Stream quality choices that can be changed live — from the car menu or the
 * phone app — and what each change requires. Values match the persisted
 * StreamSettings names (Resolution enum names, fps with 0 = Auto,
 * StreamingMode enum names).
 */
object QualityChoicePolicy {

    val RESOLUTIONS = listOf("AUTO", "RES_720", "RES_800", "RES_960", "RES_1080", "RES_1200")
    val FPS = listOf(0, 30, 60)
    val MODES = listOf("AUTO", "MSE", "WEBCODECS", "MJPEG")

    data class Choice(val resolution: String, val fps: Int, val mode: String, val keepAwake: Boolean)

    data class Change(val rebuild: Boolean, val reloadPage: Boolean, val keepAwakeChanged: Boolean)

    /** Applies the valid parts of a request; unknown values are ignored. */
    fun merge(current: Choice, resolution: String?, fps: Int?, mode: String?, keepAwake: Boolean?): Choice =
        current.copy(
            resolution = resolution?.takeIf { it in RESOLUTIONS } ?: current.resolution,
            fps = fps?.takeIf { it in FPS } ?: current.fps,
            mode = mode?.takeIf { it in MODES } ?: current.mode,
            keepAwake = keepAwake ?: current.keepAwake
        )

    /**
     * Resolution / fps: rebuild the pipeline (size change recreates the display).
     * Codec: the car page picks its decoder at load, so it reloads.
     */
    fun diff(old: Choice, new: Choice) = Change(
        rebuild = old.resolution != new.resolution || old.fps != new.fps,
        reloadPage = old.mode != new.mode,
        keepAwakeChanged = old.keepAwake != new.keepAwake
    )

    /** Manual short-side cap in px, or null for Auto. */
    fun maxShortSide(resolution: String): Int? =
        resolution.removePrefix("RES_").toIntOrNull()?.takeIf { resolution != "AUTO" }

    fun codecLabel(codec: String): String = when (codec) {
        "fmp4" -> "Hardware video (MSE)"
        "h264" -> "WebCodecs"
        "mjpeg" -> "MJPEG"
        else -> codec
    }

    /** One line for the car menu: the choice, then what is really running. */
    fun summary(choice: Choice, width: Int, height: Int, fps: Int, codec: String, thermalLimited: Boolean): String {
        val res = if (choice.resolution == "AUTO") "Auto" else choice.resolution.removePrefix("RES_") + "p"
        return "$res → ${width}×$height · $fps fps · ${codecLabel(codec)}" +
            if (thermalLimited) " · limited: phone warm" else ""
    }
}
