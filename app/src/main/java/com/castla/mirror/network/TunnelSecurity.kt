package com.castla.mirror.network

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Persisted security + tunnel configuration used by the remote access feature.
 *
 * Two independent pieces live here:
 *  - HTTP auth gate: a simple password page served by [com.castla.mirror.server.MirrorServer].
 *    A random, stable [sessionSecret] is generated once and stored so that a valid
 *    session cookie survives server (and pipeline) restarts.
 *  - Named tunnel: an optional Cloudflare Zero Trust connector token
 *    (`cloudflared tunnel run --token ...`) plus the public hostname the user
 *    configured in the dashboard, for a permanent (non-trycloudflare) URL.
 */
data class TunnelSecurityConfig(
    val authEnabled: Boolean = false,
    val authPassword: String = "",
    val namedTunnelEnabled: Boolean = false,
    val namedTunnelToken: String = "",
    val namedTunnelUrl: String = ""
) {
    companion object {
        private const val TAG = "TunnelSecurity"
        private const val PREFS_NAME = "tunnel_security"
        private const val KEY_AUTH_ENABLED = "auth_enabled"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        private const val KEY_AUTH_SECRET = "auth_secret"
        private const val KEY_NAMED_ENABLED = "named_enabled"
        private const val KEY_NAMED_TOKEN = "named_token"
        private const val KEY_NAMED_URL = "named_url"

        private val sessionSecretCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun load(context: Context): TunnelSecurityConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TunnelSecurityConfig(
                authEnabled = prefs.getBoolean(KEY_AUTH_ENABLED, false),
                authPassword = prefs.getString(KEY_AUTH_PASSWORD, "") ?: "",
                namedTunnelEnabled = prefs.getBoolean(KEY_NAMED_ENABLED, false),
                namedTunnelToken = prefs.getString(KEY_NAMED_TOKEN, "") ?: "",
                namedTunnelUrl = prefs.getString(KEY_NAMED_URL, "") ?: ""
            )
        }

        fun save(context: Context, config: TunnelSecurityConfig) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTH_ENABLED, config.authEnabled)
                .putString(KEY_AUTH_PASSWORD, config.authPassword)
                .putBoolean(KEY_NAMED_ENABLED, config.namedTunnelEnabled)
                .putString(KEY_NAMED_TOKEN, config.namedTunnelToken)
                .putString(KEY_NAMED_URL, config.namedTunnelUrl)
                .apply()
            sessionSecretCache.remove(PREFS_NAME)
            Log.i(TAG, "Tunnel security config saved")
        }

        /**
         * Server-side session secret, stable for the lifetime of the install.
         * Generated once on first use; intentionally NOT exposed in the app UI.
         */
        @Synchronized
        fun sessionSecret(context: Context): String {
            val cached = sessionSecretCache[PREFS_NAME]
            if (cached != null) return cached
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var secret = prefs.getString(KEY_AUTH_SECRET, null)
            if (secret.isNullOrBlank()) {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                secret = bytes.toHex()
                prefs.edit().putString(KEY_AUTH_SECRET, secret).apply()
                Log.i(TAG, "Generated new session secret")
            }
            sessionSecretCache[PREFS_NAME] = secret
            return secret
        }

        /**
         * Session token sent to the browser as the `castla_auth` cookie value.
         * HMAC-style digest of secret + password so the client cannot forge it.
         */
        fun sessionToken(context: Context, password: String): String {
            val seed = sessionSecret(context) + "\u0000" + password
            return sha256Hex(seed)
        }

        fun isValidSession(context: Context, config: TunnelSecurityConfig, cookie: String?): Boolean {
            if (!config.authEnabled || config.authPassword.isEmpty()) return true
            if (cookie.isNullOrEmpty()) return false
            val expected = sessionToken(context, config.authPassword)
            return MessageDigest.isEqual(cookie.toByteArray(), expected.toByteArray())
        }

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).toHex()

        /** True when a connector token has been saved (regardless of the toggle). */
        fun hasNamedTunnel(config: TunnelSecurityConfig): Boolean =
            config.namedTunnelToken.isNotBlank()

        /**
         * Prefer the permanent (stable-URL) tunnel whenever a connector token is
         * present. A saved token is meaningless for quick tunnels, and silently
         * falling back to a quick tunnel means every reconnect spawns a NEW
         * *.trycloudflare.com URL — which drops an in-progress mirroring session
         * because the client can no longer reach the old URL. The in-UI toggle
         * only exists to *disable* a token the user no longer wants.
         */
        fun shouldUseNamedTunnel(config: TunnelSecurityConfig): Boolean =
            hasNamedTunnel(config)
    }
}