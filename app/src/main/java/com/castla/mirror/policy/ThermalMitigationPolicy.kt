package com.castla.mirror.policy

import android.os.PowerManager

/**
 * Pure thermal-mitigation policy.
 *
 * Decides how aggressively the mirroring pipeline must back off to keep the
 * device from reaching a framework thermal SHUTDOWN (which powers the phone off
 * instantly) or a thermal REBOOT caused by an orphaned virtual display.
 *
 * Android's thermal ladder (PowerManager.THERMAL_STATUS_*):
 *  - SEVERE(3):    significant throttling — drop resolution + fps.
 *  - CRITICAL(4):  platform has done everything to reduce power.
 *  - EMERGENCY(5): key components shutting down — LAST WARNING before shutdown.
 *  - SHUTDOWN(6):  framework calls powerManager.shutdown() — phone powers off.
 *
 * The app MUST reduce load at CRITICAL/EMERGENCY. Previously it only showed a
 * toast and kept running, which let the device slide into a framework SHUTDOWN.
 * At EMERGENCY we also request a clean session stop so the SoC can cool — a
 * clean teardown releases the virtual display and avoids the orphaned-VD reboot.
 *
 * `getThermalHeadroom()` is a more sensitive *early* signal (predicts seconds
 * ahead). We escalate on low headroom even before the status callback fires,
 * per Android's ADPF guidance ("better to prevent the thermal status from being
 * raised").
 */
object ThermalMitigationPolicy {

    const val MIN_BITRATE = 500_000

    private const val FACTOR_LIGHT = 0.85
    private const val FACTOR_MODERATE = 0.6
    private const val FACTOR_SEVERE = 0.4
    private const val FACTOR_CRITICAL = 0.25
    private const val FACTOR_EMERGENCY = 0.15
    /** Forecast headroom at/above which we act like SEVERE (1.0 == severe throttling). */
    const val HEADROOM_SEVERE = 1.0f
    /** Forecast headroom at/above which we pre-emptively back off like MODERATE. */
    const val HEADROOM_MODERATE = 0.9f

    data class Action(
        /** Bitrate multiplier applied to the pre-thermal baseline. Null = leave unchanged. */
        val bitrateFactor: Double?,
        /** JPEG (MJPEG) encoder fps cap. Null = leave unchanged. */
        val jpegFps: Int?,
        /** Hard fps override for the encoder pipeline. Null = clear override. */
        val fpsOverride: Int?,
        /** Resolution cap (height) for the encoder pipeline. Null = clear cap. */
        val maxHeight: Int?,
        /** Stop system-audio capture to shed CPU. */
        val stopAudio: Boolean,
        /** Tear down the secondary (split-view) encoder to shed heat. */
        val stopSecondary: Boolean,
        /** Cleanly stop the mirroring session so the device can cool (prevents thermal shutdown/reboot). */
        val emergencyStop: Boolean,
        val label: String
    ) {
        companion object {
            val NONE = Action(null, null, null, null, false, false, false, "none")
        }
    }

    fun evaluate(status: Int, headroom: Float? = null): Action {
        val statusAction = when (status) {
            PowerManager.THERMAL_STATUS_EMERGENCY ->
                Action(FACTOR_EMERGENCY, 5, 10, 480, stopAudio = true, stopSecondary = true, emergencyStop = true, "emergency")
            PowerManager.THERMAL_STATUS_CRITICAL ->
                Action(FACTOR_CRITICAL, 6, 12, 480, stopAudio = true, stopSecondary = true, emergencyStop = false, "critical")
            PowerManager.THERMAL_STATUS_SEVERE ->
                Action(FACTOR_SEVERE, 8, 15, 720, stopAudio = true, stopSecondary = true, emergencyStop = false, "severe")
            PowerManager.THERMAL_STATUS_MODERATE ->
                Action(FACTOR_MODERATE, 12, 20, null, stopAudio = false, stopSecondary = true, emergencyStop = false, "moderate")
            PowerManager.THERMAL_STATUS_LIGHT ->
                Action(FACTOR_LIGHT, null, null, null, stopAudio = false, stopSecondary = false, emergencyStop = false, "light")
            else -> Action.NONE
        }

        // getThermalHeadroom(): 0.0 = no throttling, 1.0 = THERMAL_STATUS_SEVERE,
        // >1.0 = beyond it. HIGHER IS WORSE. (This used to be read the other way
        // round: a cool S20 Ultra reporting 0.61 was treated as "severe" — 500 kbps,
        // 15 fps and a pipeline rebuild on every session — and 0.0 as "shutdown".)
        val headroomAction = headroom?.takeIf { !it.isNaN() }?.let { h ->
            when {
                h >= HEADROOM_SEVERE ->
                    Action(FACTOR_SEVERE, 8, 15, 720, stopAudio = true, stopSecondary = true, emergencyStop = false, "headroom_severe")
                h >= HEADROOM_MODERATE ->
                    Action(FACTOR_MODERATE, 12, 20, null, stopAudio = false, stopSecondary = true, emergencyStop = false, "headroom_moderate")
                else -> null
            }
        }

        return if (headroomAction == null) statusAction else merge(statusAction, headroomAction)
    }

    /** Combine two actions, keeping the most conservative (highest-reduction) choice for each field. */
    private fun merge(a: Action, b: Action): Action {
        val factorA = a.bitrateFactor ?: 1.0
        val factorB = b.bitrateFactor ?: 1.0
        val fpsA = a.fpsOverride ?: Int.MAX_VALUE
        val fpsB = b.fpsOverride ?: Int.MAX_VALUE
        val hA = a.maxHeight ?: Int.MAX_VALUE
        val hB = b.maxHeight ?: Int.MAX_VALUE
        return Action(
            bitrateFactor = minOf(factorA, factorB).takeIf { it < 1.0 },
            jpegFps = listOfNotNull(a.jpegFps, b.jpegFps).minOrNull(),
            fpsOverride = minOf(fpsA, fpsB).takeIf { it != Int.MAX_VALUE },
            maxHeight = minOf(hA, hB).takeIf { it != Int.MAX_VALUE },
            stopAudio = a.stopAudio || b.stopAudio,
            stopSecondary = a.stopSecondary || b.stopSecondary,
            emergencyStop = a.emergencyStop || b.emergencyStop,
            label = if (b.label.startsWith("headroom")) b.label else a.label
        )
    }
}
