package com.castla.mirror.policy

import com.castla.mirror.policy.ViewportChangePolicy.Decision
import com.castla.mirror.policy.ViewportChangePolicy.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportChangePolicyTest {

    private val full = Viewport(857, 1096)

    @Test
    fun `first viewport of a session is applied at once`() {
        assertEquals(Decision.APPLY_NOW, ViewportChangePolicy.decide(applied = null, requested = full))
    }

    @Test
    fun `same size is ignored`() {
        assertEquals(Decision.IGNORE, ViewportChangePolicy.decide(full, Viewport(857, 1096)))
    }

    @Test
    fun `tesla keyboard opening (same width, much shorter) never rebuilds the display`() {
        // Field log: 857x1096 -> 857x668 while typing; the display was destroyed and
        // recreated twice in 2 s and system_server went down.
        assertEquals(Decision.IGNORE, ViewportChangePolicy.decide(full, Viewport(857, 668)))
    }

    @Test
    fun `small jitter is ignored`() {
        assertEquals(Decision.IGNORE, ViewportChangePolicy.decide(full, Viewport(860, 1090)))
    }

    @Test
    fun `a real change waits until it has been stable`() {
        assertEquals(Decision.DEBOUNCE, ViewportChangePolicy.decide(full, Viewport(1659, 791)))
        assertEquals(Decision.DEBOUNCE, ViewportChangePolicy.decide(full, Viewport(1200, 1096)))
    }

    @Test
    fun `display recreates are rate limited`() {
        assertEquals(0L, ViewportChangePolicy.delayBeforeRebuild(lastRebuildAtMs = 0, nowMs = 100_000))
        assertEquals(ViewportChangePolicy.MIN_REBUILD_INTERVAL_MS - 3_000,
            ViewportChangePolicy.delayBeforeRebuild(lastRebuildAtMs = 100_000, nowMs = 103_000))
    }
}
