package com.castla.mirror.backup

/**
 * What a settings backup (JSON export/import) may contain.
 *
 * Stream settings and the car's favorites/recent apps are always included.
 * The access password and the Cloudflare tunnel token are secrets: only with
 * the user's explicit choice. The session secret (signs login cookies) is
 * never exported — a restored install creates its own, so old logins end.
 */
object BackupPolicy {

    const val FORMAT = "castla-backup"
    const val VERSION = 1

    const val SETTINGS_FILE = "castla_settings"
    const val LAUNCHER_FILE = "castla_launcher"
    const val SECURITY_FILE = "tunnel_security"

    private val SECRET_KEYS = setOf("auth_enabled", "auth_password", "named_enabled", "named_token", "named_url")

    val FILES = listOf(SETTINGS_FILE, LAUNCHER_FILE, SECURITY_FILE)

    fun isIncluded(file: String, key: String, includeSecrets: Boolean): Boolean = when (file) {
        SETTINGS_FILE, LAUNCHER_FILE -> true
        SECURITY_FILE -> includeSecrets && key in SECRET_KEYS
        else -> false
    }

    fun canImport(format: String?, version: Int): Boolean = format == FORMAT && version in 1..VERSION
}

/** Favorites / recent app lists synced between the car page and the phone. */
object LauncherLists {
    const val MAX_FAVORITES = 60
    const val MAX_RECENT = 8
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun sanitize(items: List<String>, max: Int): List<String> =
        items.map { it.trim() }.filter { PACKAGE.matches(it) }.distinct().take(max)
}
