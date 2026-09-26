package com.castla.mirror.server

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import com.castla.mirror.network.TunnelSecurityConfig
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class MirrorServerAuthTest {

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var prefs: SharedPreferences
    private lateinit var server: MirrorServer
    private var password = "correct-horse"

    @Before
    fun setup() {
        assetManager = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.assets } returns assetManager
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString("auth_password", any()) } answers { password }
        every { prefs.getString("auth_secret", any()) } returns "a".repeat(64)
        server = MirrorServer(context)
    }

    private fun validCookie() = "castla_auth=" + TunnelSecurityConfig.sessionToken(context, password)

    private fun mockSession(
        uri: String,
        cookie: String? = null,
        method: NanoHTTPD.Method = NanoHTTPD.Method.GET,
        query: String? = null
    ): NanoHTTPD.IHTTPSession {
        val session = mockk<NanoHTTPD.IHTTPSession>(relaxed = true)
        every { session.uri } returns uri
        every { session.method } returns method
        every { session.queryParameterString } returns query
        every { session.remoteIpAddress } returns "192.168.1.100"
        every { session.headers } returns (if (cookie != null) mapOf("cookie" to cookie) else emptyMap())
        return session
    }

    private fun callServeHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val method = MirrorServer::class.java.getDeclaredMethod("serveHttp", NanoHTTPD.IHTTPSession::class.java)
        method.isAccessible = true
        return method.invoke(server, session) as NanoHTTPD.Response
    }

    private fun body(r: NanoHTTPD.Response) = r.data.bufferedReader().readText()

    @Test
    fun `without a password nothing is served`() {
        password = ""
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html>launcher</html>".toByteArray())
        for (uri in listOf("/", "/api/apps", "/js/main.js")) {
            val r = callServeHttp(mockSession(uri))
            assertEquals(uri, NanoHTTPD.Response.Status.FORBIDDEN, r.status)
            assertFalse(uri, body(r).contains("launcher"))
        }
    }

    @Test
    fun `short password counts as no password`() {
        password = "1234567"
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, callServeHttp(mockSession("/")).status)
    }

    @Test
    fun `without login the launcher is replaced by the login page`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html>launcher</html>".toByteArray())
        every { assetManager.open("web/login.html") } returns ByteArrayInputStream("<html>login form</html>".toByteArray())
        val r = callServeHttp(mockSession("/"))
        assertTrue(body(r).contains("login form"))
    }

    @Test
    fun `app list and icons need a login`() {
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, callServeHttp(mockSession("/api/apps")).status)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, callServeHttp(mockSession("/api/icon")).status)
    }

    @Test
    fun `wrong or forged cookie is not a login`() {
        val r = callServeHttp(mockSession("/api/apps", cookie = "castla_auth=deadbeef"))
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, r.status)
    }

    @Test
    fun `root serves index page with a valid login`() {
        every { assetManager.open("web/index.html") } returns ByteArrayInputStream("<html>test</html>".toByteArray())
        val response = callServeHttp(mockSession("/", cookie = validCookie()))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("text/html", response.mimeType)
    }

    @Test
    fun `JS sub-resource serves correctly`() {
        every { assetManager.open("web/js/main.js") } returns ByteArrayInputStream("console.log('test')".toByteArray())
        val response = callServeHttp(mockSession("/js/main.js", cookie = validCookie()))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/javascript", response.mimeType)
    }

    @Test
    fun `CSS sub-resource serves correctly`() {
        every { assetManager.open("web/css/player.css") } returns ByteArrayInputStream("body{}".toByteArray())
        val response = callServeHttp(mockSession("/css/player.css", cookie = validCookie()))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("text/css", response.mimeType)
    }

    @Test
    fun `PNG sub-resource serves correctly`() {
        every { assetManager.open("web/img/logo.png") } returns ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50))
        val response = callServeHttp(mockSession("/img/logo.png", cookie = validCookie()))
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("image/png", response.mimeType)
    }

    @Test
    fun `missing asset returns 404`() {
        every { assetManager.open(any()) } throws java.io.IOException("not found")
        val response = callServeHttp(mockSession("/nonexistent.xyz", cookie = validCookie()))
        assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.status)
    }

    @Test
    fun `path traversal is rejected even when logged in`() {
        val response = callServeHttp(mockSession("/../shared_prefs/tunnel_security.xml", cookie = validCookie()))
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
    }

    @Test
    fun `login by GET or with the password in the URL is refused`() {
        assertEquals(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
            callServeHttp(mockSession("/auth", query = "password=correct-horse")).status)
        val post = callServeHttp(mockSession("/auth", method = NanoHTTPD.Method.POST, query = "password=correct-horse"))
        assertNotEquals(NanoHTTPD.Response.Status.REDIRECT, post.status)
    }

    @Test
    fun `security headers are always set`() {
        val r = callServeHttp(mockSession("/"))
        assertEquals("DENY", r.getHeader("X-Frame-Options"))
        assertEquals("nosniff", r.getHeader("X-Content-Type-Options"))
    }
}
