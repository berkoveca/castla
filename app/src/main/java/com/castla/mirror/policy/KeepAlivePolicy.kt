package com.castla.mirror.policy

/**
 * WebSocket keepalive timing for the browser <-> Cloudflare <-> phone path.
 * Numbers are mirrored in `assets/web/js/main.js` (the browser cannot import this).
 *
 * Cloudflare closes a proxied WebSocket that carries no data for 100 s. The
 * control and audio sockets are routinely idle that long (no touches, audio
 * off), so without traffic they were silently cut and had to reconnect.
 */
object KeepAlivePolicy {
    const val CLOUDFLARE_IDLE_TIMEOUT_MS = 100_000L
    /** Server sends a ping frame (video/audio) or a {"type":"ka"} message (control). */
    const val SERVER_INTERVAL_MS = 20_000L
    /** Client closes + reconnects a control socket that has been silent this long (half-open link). */
    const val CLIENT_SILENCE_TIMEOUT_MS = 50_000L
}
