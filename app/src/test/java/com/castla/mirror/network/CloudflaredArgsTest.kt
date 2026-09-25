package com.castla.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredArgsTest {

    private val args = CloudflaredArgs.namedTunnel("/data/app/lib/libcloudflared.so", "TOKEN123")

    private fun valueOf(flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    @Test
    fun `binary comes first and subcommand is tunnel run`() {
        assertEquals("/data/app/lib/libcloudflared.so", args.first())
        assertEquals("tunnel", args[1])
        assertTrue(args.indexOf("run") > args.indexOf("tunnel"))
    }

    @Test
    fun `uses QUIC because http2 cannot carry WebSockets`() {
        // cloudflare/cloudflared#1208: WebSockets break with --protocol http2
        assertEquals("quic", valueOf("--protocol"))
    }

    @Test
    fun `keeps two edge connections for redundancy`() {
        assertEquals("2", valueOf("--ha-connections"))
    }

    @Test
    fun `ipv4 edge and no autoupdate`() {
        assertEquals("4", valueOf("--edge-ip-version"))
        assertTrue(args.contains("--no-autoupdate"))
    }

    @Test
    fun `token is passed after run and is the last argument`() {
        assertEquals("TOKEN123", args.last())
        assertEquals("--token", args[args.size - 2])
        assertTrue(args.indexOf("--token") > args.indexOf("run"))
    }

    @Test
    fun `global flags precede the run subcommand`() {
        val run = args.indexOf("run")
        listOf("--protocol", "--ha-connections", "--edge-ip-version", "--no-autoupdate").forEach {
            assertTrue("$it must come before run", args.indexOf(it) in 0 until run)
        }
    }

    @Test
    fun `redacted form never contains the token`() {
        val shown = CloudflaredArgs.redacted(args)
        assertFalse(shown, shown.contains("TOKEN123"))
        assertFalse(shown, shown.contains("libcloudflared"))
        assertTrue(shown, shown.contains("--protocol quic"))
        assertTrue(shown, shown.endsWith("--token <redacted>"))
    }
}
