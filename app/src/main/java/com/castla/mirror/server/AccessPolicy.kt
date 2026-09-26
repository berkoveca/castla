package com.castla.mirror.server

import java.net.URI
import java.net.URLDecoder

/**
 * Who may reach what on the mirror server. The server is reachable from the
 * public internet (Cloudflare tunnel) and from any device on the phone's
 * network, and a session can see the phone's screen and control it — so:
 *
 * - without a configured password (>= [MIN_PASSWORD_LENGTH]) NOTHING is served
 *   except a "set a password in the app" page;
 * - with one, every page, API (app list, icons) and live connection requires a
 *   valid session cookie; only the favicon is public (the login page is served
 *   in place of anything else).
 */
object AccessPolicy {

    enum class Decision { ALLOW, LOGIN, SETUP_REQUIRED }

    const val MIN_PASSWORD_LENGTH = 8
    const val SESSION_MAX_AGE_S = 30L * 24 * 3600

    private val PUBLIC_PATHS = setOf("/favicon.ico")

    fun passwordIsStrongEnough(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH

    fun httpDecision(path: String, validSession: Boolean, passwordSet: Boolean): Decision = when {
        !passwordSet -> Decision.SETUP_REQUIRED
        validSession || path in PUBLIC_PATHS -> Decision.ALLOW
        else -> Decision.LOGIN
    }

    /** Rejects traversal and odd separators, also when percent-encoded. */
    fun isSafePath(path: String): Boolean {
        val decoded = try { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { return false }
        return listOf(path, decoded).none { p -> ".." in p || '\\' in p || '\u0000' in p }
    }

    /**
     * Cross-site WebSocket hijacking guard: a browser page on another site must
     * not open a control connection with the car's cookie. Browsers always send
     * Origin on WebSockets; a missing one (non-browser client) still needs the cookie.
     */
    fun originAllowed(origin: String?, host: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        if (host.isNullOrBlank()) return false
        return try {
            val u = URI(origin)
            val originHost = if (u.port >= 0) "${u.host}:${u.port}" else u.host
            originHost.equals(host, ignoreCase = true) || u.host.equals(host.substringBefore(':'), ignoreCase = true) && !host.contains(':')
        } catch (_: Exception) {
            false
        }
    }

    /** Tunnel traffic arrives from cloudflared on loopback; the real client is in CF-Connecting-IP. */
    fun clientKey(remoteIp: String?, cfConnectingIp: String?): String {
        val remote = remoteIp.orEmpty()
        val loopback = remote.startsWith("127.") || remote == "::1" || remote == "0:0:0:0:0:0:0:1"
        return if (loopback && !cfConnectingIp.isNullOrBlank()) cfConnectingIp.trim() else remote
    }

    fun isHttps(headers: Map<String, String>): Boolean =
        headers["x-forwarded-proto"].equals("https", ignoreCase = true) ||
            headers["cf-visitor"].orEmpty().contains("\"https\"")

    fun sessionCookie(token: String, secure: Boolean): String =
        "castla_auth=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=$SESSION_MAX_AGE_S" +
            if (secure) "; Secure" else ""
}
