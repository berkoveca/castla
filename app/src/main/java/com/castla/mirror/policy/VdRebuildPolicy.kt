package com.castla.mirror.policy

/**
 * What a pipeline rebuild does with the virtual display.
 *
 * Resizing a live virtual display does not re-layout activities already on it:
 * Android keeps them at their old bounds (size-compat letterbox), so the app
 * shows up as a small box in a corner of the car screen. A real size change
 * therefore recreates the display and relaunches its content. A rebuild at
 * the same size (reconnect, fps step, encoder restart) only swaps the encoder
 * surface, so the app on the car screen is not restarted.
 */
object VdRebuildPolicy {

    enum class Action { CREATE, SWAP_SURFACE, RECREATE }

    /** [forceRecreate]: the display itself is broken (e.g. stuck black), a surface swap would keep it. */
    fun decide(
        hasDisplay: Boolean, oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int,
        forceRecreate: Boolean = false
    ): Action = when {
        !hasDisplay -> Action.CREATE
        forceRecreate -> Action.RECREATE
        oldWidth == newWidth && oldHeight == newHeight -> Action.SWAP_SURFACE
        else -> Action.RECREATE
    }
}
