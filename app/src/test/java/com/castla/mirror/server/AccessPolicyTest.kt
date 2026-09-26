package com.castla.mirror.server

import com.castla.mirror.server.AccessPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {

    @Test
    fun `nothing is reachable without a password configured`() {
        for (p in listOf("/", "/index.html", "/api/apps", "/api/icon", "/js/main.js")) {
            assertEquals(p, Decision.SETUP_REQUIRED, AccessPolicy.httpDecision(p, validSession = false, passwordSet = false))
        }
    }

    @Test
    fun `app list and icons need a login like every page`() {
        // They used to bypass the gate and leaked the installed-app list.
        assertEquals(Decision.LOGIN, AccessPolicy.httpDecision("/api/apps", validSession = false, passwordSet = true))
        assertEquals(Decision.LOGIN, AccessPolicy.httpDecision("/api/icon", validSession = false, passwordSet = true))
        assertEquals(Decision.LOGIN, AccessPolicy.httpDecision("/index.html", validSession = false, passwordSet = true))
        assertEquals(Decision.LOGIN, AccessPolicy.httpDecision("/js/main.js", validSession = false, passwordSet = true))
    }

    @Test
    fun `a valid session gets everything`() {
        assertEquals(Decision.ALLOW, AccessPolicy.httpDecision("/api/apps", validSession = true, passwordSet = true))
    }

    @Test
    fun `only the favicon is public`() {
        assertEquals(Decision.ALLOW, AccessPolicy.httpDecision("/favicon.ico", validSession = false, passwordSet = true))
    }

    @Test
    fun `short passwords do not count as set`() {
        assertFalse(AccessPolicy.passwordIsStrongEnough("1234567"))
        assertTrue(AccessPolicy.passwordIsStrongEnough("12345678"))
        assertFalse(AccessPolicy.passwordIsStrongEnough(""))
    }

    @Test
    fun `path traversal is refused`() {
        assertFalse(AccessPolicy.isSafePath("/../shared_prefs/x"))
        assertFalse(AccessPolicy.isSafePath("/js/..%2f..%2fx"))
        assertFalse(AccessPolicy.isSafePath("/a\\\\b"))
        assertTrue(AccessPolicy.isSafePath("/js/main.js"))
    }

    @Test
    fun `websocket origin must match the host`() {
        assertTrue(AccessPolicy.originAllowed("https://teslaa.site", "teslaa.site"))
        assertTrue(AccessPolicy.originAllowed("http://192.168.43.1:9090", "192.168.43.1:9090"))
        assertFalse(AccessPolicy.originAllowed("https://evil.example", "teslaa.site"))
        assertTrue(AccessPolicy.originAllowed(null, "teslaa.site"))
    }

    @Test
    fun `client key uses cloudflare header only for tunnel traffic`() {
        assertEquals("203.0.113.9", AccessPolicy.clientKey("127.0.0.1", "203.0.113.9"))
        assertEquals("192.168.43.5", AccessPolicy.clientKey("192.168.43.5", "203.0.113.9"))
        assertEquals("127.0.0.1", AccessPolicy.clientKey("127.0.0.1", null))
    }

    @Test
    fun `cookie is httponly samesite and secure over https`() {
        val c = AccessPolicy.sessionCookie("tok", secure = true)
        assertTrue(c, c.contains("HttpOnly") && c.contains("SameSite=Lax") && c.contains("Secure"))
        assertFalse(AccessPolicy.sessionCookie("tok", secure = false).contains("Secure"))
    }

    @Test
    fun `https detection from proxy headers`() {
        assertTrue(AccessPolicy.isHttps(mapOf("x-forwarded-proto" to "https")))
        assertTrue(AccessPolicy.isHttps(mapOf("cf-visitor" to "{\"scheme\":\"https\"}")))
        assertFalse(AccessPolicy.isHttps(emptyMap()))
    }
}
