package com.castla.mirror.policy

/**
 * Pure-logic policy for browser-disconnect handling.
 *
 * The Tesla browser socket drops briefly on tunnel/LTE blips and while the
 * phone screen is off; tearing down the pipeline in that window kills
 * wake-locks and forces a full virtual-display rebuild. This policy gives a
 * grace period long enough for a reconnect and defers teardown until the
 * screen turns back on.
 */
object DisconnectPolicy {

    /**
     * Normal grace period. The car reaches the phone through the Cloudflare
     * tunnel over LTE: a cloudflared reconnect (restart backoff + edge
     * registration + the browser's own reconnect backoff) routinely takes longer
     * than a tab refresh. Tearing the virtual display down and rebuilding it on
     * every such blip was pure churn (and VD churn is a reboot risk), so keep
     * the pipeline alive long enough to ride out a tunnel reconnect.
     */
    const val DEFAULT_GRACE_MS = 20_000L

    /** Extended grace while the physical screen is off. */
    const val SCREEN_OFF_GRACE_MS = 30_000L

    /** Returns the appropriate grace period based on screen state. */
    fun graceMs(isScreenOff: Boolean): Long =
        if (isScreenOff) SCREEN_OFF_GRACE_MS else DEFAULT_GRACE_MS

    /**
     * Whether the pipeline should be torn down right now.
     *
     * Returns `false` when the screen is off — teardown is deferred
     * until the screen turns back on so that wake-locks stay held.
     */
    fun shouldTeardown(isScreenOff: Boolean, isBrowserConnected: Boolean): Boolean =
        !isBrowserConnected && !isScreenOff
}
