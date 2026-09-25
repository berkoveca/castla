package com.castla.mirror.policy

import com.castla.mirror.policy.VdRebuildPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class VdRebuildPolicyTest {

    @Test
    fun `no display yet means create`() {
        assertEquals(Action.CREATE, VdRebuildPolicy.decide(false, 800, 1024, 800, 1024))
    }

    @Test
    fun `same size only swaps the surface - no app relaunch on reconnect or fps change`() {
        assertEquals(Action.SWAP_SURFACE, VdRebuildPolicy.decide(true, 800, 1024, 800, 1024))
    }

    @Test
    fun `size change recreates so apps lay out for the new size instead of a small letterbox`() {
        assertEquals(Action.RECREATE, VdRebuildPolicy.decide(true, 368, 800, 800, 1024))
        assertEquals(Action.RECREATE, VdRebuildPolicy.decide(true, 800, 1024, 928, 720))
    }

    @Test
    fun `unknown previous size recreates`() {
        assertEquals(Action.RECREATE, VdRebuildPolicy.decide(true, 0, 0, 800, 1024))
    }
}
