package com.castla.mirror.policy

import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAlivePolicyTest {

    @Test
    fun `server keepalive fires well inside Cloudflare's idle timeout`() {
        // Cloudflare closes a WebSocket that carries no data for 100 s.
        assertTrue(KeepAlivePolicy.SERVER_INTERVAL_MS * 3 <= KeepAlivePolicy.CLOUDFLARE_IDLE_TIMEOUT_MS)
    }

    @Test
    fun `client declares the control socket dead only after missing several keepalives`() {
        assertTrue(KeepAlivePolicy.CLIENT_SILENCE_TIMEOUT_MS >= KeepAlivePolicy.SERVER_INTERVAL_MS * 2)
        // ...but before Cloudflare itself would have given up on it
        assertTrue(KeepAlivePolicy.CLIENT_SILENCE_TIMEOUT_MS < KeepAlivePolicy.CLOUDFLARE_IDLE_TIMEOUT_MS)
    }
}
