package com.castla.mirror.policy

import com.castla.mirror.policy.QualityChoicePolicy.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityChoicePolicyTest {

    private val auto = Choice("AUTO", 0, "AUTO", keepAwake = true)

    @Test
    fun `valid request values are applied`() {
        val c = QualityChoicePolicy.merge(auto, resolution = "RES_960", fps = 60, mode = "MSE", keepAwake = false)
        assertEquals(Choice("RES_960", 60, "MSE", keepAwake = false), c)
    }

    @Test
    fun `missing and invalid values keep the current choice`() {
        val c = QualityChoicePolicy.merge(auto, resolution = "RES_4K", fps = 17, mode = "h265", keepAwake = null)
        assertEquals(auto, c)
    }

    @Test
    fun `resolution change rebuilds, codec change reloads the car page`() {
        val res = QualityChoicePolicy.diff(auto, auto.copy(resolution = "RES_720"))
        assertTrue(res.rebuild); assertFalse(res.reloadPage)
        val fps = QualityChoicePolicy.diff(auto, auto.copy(fps = 30))
        assertTrue(fps.rebuild); assertFalse(fps.reloadPage)
        val mode = QualityChoicePolicy.diff(auto, auto.copy(mode = "MJPEG"))
        assertFalse(mode.rebuild); assertTrue(mode.reloadPage)
        val awake = QualityChoicePolicy.diff(auto, auto.copy(keepAwake = false))
        assertFalse(awake.rebuild); assertFalse(awake.reloadPage); assertTrue(awake.keepAwakeChanged)
    }

    @Test
    fun `no change means nothing to do`() {
        val d = QualityChoicePolicy.diff(auto, auto)
        assertFalse(d.rebuild || d.reloadPage || d.keepAwakeChanged)
    }

    @Test
    fun `manual resolution maps to its short side, auto to none`() {
        assertEquals(960, QualityChoicePolicy.maxShortSide("RES_960"))
        assertNull(QualityChoicePolicy.maxShortSide("AUTO"))
    }

    @Test
    fun `summary shows what auto picked and why it is limited`() {
        assertEquals(
            "Auto → 704×1360 · 30 fps · Hardware video (MSE)",
            QualityChoicePolicy.summary(auto, 704, 1360, 30, "fmp4", thermalLimited = false)
        )
        assertEquals(
            "960p → 704×1360 · 60 fps · WebCodecs · limited: phone warm",
            QualityChoicePolicy.summary(Choice("RES_960", 60, "AUTO", true), 704, 1360, 60, "h264", thermalLimited = true)
        )
        assertEquals(
            "Auto → 640×1408 · 30 fps · MJPEG",
            QualityChoicePolicy.summary(auto, 640, 1408, 30, "mjpeg", thermalLimited = false)
        )
    }
}
