package com.castla.mirror.policy

import kotlin.math.abs

/**
 * Decides whether a viewport report from the car page may rebuild the stream.
 *
 * A rebuild at a new size destroys and recreates the virtual display and
 * relaunches the app on it. Doing that twice within ~2 s while the app was still
 * starting (Tesla keyboard opening then closing: 857x1096 → 857x668 → 857x1096)
 * wedged system_server — touch injection blocked for 2.3 s and the phone
 * soft-rebooted seconds later. So:
 *
 * - the first viewport of a session applies at once (the display starts at a
 *   phone-derived size);
 * - the on-screen keyboard (same width, shorter) is ignored — the page letterboxes;
 * - jitter under [MIN_CHANGE_FRACTION] is ignored;
 * - any other change must stay stable for [STABLE_MS] before it applies;
 * - display recreates are at least [MIN_REBUILD_INTERVAL_MS] apart.
 */
object ViewportChangePolicy {

    data class Viewport(val width: Int, val height: Int)

    enum class Decision { APPLY_NOW, DEBOUNCE, IGNORE }

    const val STABLE_MS = 4_000L
    const val MIN_REBUILD_INTERVAL_MS = 10_000L
    private const val MIN_CHANGE_FRACTION = 0.05
    private const val WIDTH_SAME_FRACTION = 0.02

    fun decide(applied: Viewport?, requested: Viewport): Decision {
        if (applied == null) return Decision.APPLY_NOW
        val dw = abs(requested.width - applied.width).toDouble() / applied.width.coerceAtLeast(1)
        val dh = abs(requested.height - applied.height).toDouble() / applied.height.coerceAtLeast(1)
        if (dw < MIN_CHANGE_FRACTION && dh < MIN_CHANGE_FRACTION) return Decision.IGNORE
        val keyboard = dw <= WIDTH_SAME_FRACTION && requested.height < applied.height
        if (keyboard) return Decision.IGNORE
        return Decision.DEBOUNCE
    }

    /** Extra wait so two display recreates are never closer than [MIN_REBUILD_INTERVAL_MS]. */
    fun delayBeforeRebuild(lastRebuildAtMs: Long, nowMs: Long): Long {
        if (lastRebuildAtMs <= 0) return 0
        return (lastRebuildAtMs + MIN_REBUILD_INTERVAL_MS - nowMs).coerceAtLeast(0)
    }
}
