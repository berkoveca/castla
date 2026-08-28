package com.castla.mirror.diagnostics

/**
 * Redacts URLs, intent extras, and shell command bodies from log messages
 * before they are persisted to disk. Pure object; safe to call concurrently.
 */
object DiagnosticSanitizer {

    private const val MAX_LEN = 500
    private const val MAX_HOST_LEN = 128
    private val HOST_STRIP = Regex("[\\x00-\\x20\\x7F]")
    private val URL_REGEX = Regex("https?://[^\\s\"]+")
    private val EXTRA_REGEX = Regex("--es\\s+(\\w+)\\s+\\S+")
    private val EXECUTING_PREFIX = Regex("(Executing:\\s*).+", RegexOption.DOT_MATCHES_ALL)

    /** Returns scheme + host only (no path/query/fragment). Sentinel on parse failure. */
    fun redactUrl(url: String): String {
        if (url.isBlank()) return "<invalid-url>"
        return try {
            val u = java.net.URI(url)
            val scheme = u.scheme ?: return "<invalid-url>"
            val host = u.host ?: return "<invalid-url>"
            "$scheme://$host"
        } catch (_: Throwable) {
            "<invalid-url>"
        }
    }

    /**
     * Host header is untrusted client input: strip control chars and whitespace
     * (log-line injection) and cap length. Legit hosts never contain either.
     */
    fun sanitizeHost(host: String?): String {
        if (host.isNullOrBlank()) return "<none>"
        var s = HOST_STRIP.replace(host, "")
        if (s.isEmpty()) return "<none>"
        if (s.length > MAX_HOST_LEN) s = s.substring(0, MAX_HOST_LEN) + "…"
        return s
    }

    /** Masks the last IPv4 octet — logs are shared on public issues, but the subnet prefix stays comparable. */
    fun maskIp(ip: String?): String {
        if (ip.isNullOrBlank()) return "<none>"
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.***" else "<masked>"
    }

    /** Strips PII-prone fragments and caps at [MAX_LEN] chars. Safe inputs pass through. */
    fun safeMessage(msg: String): String {
        if (msg.isEmpty()) return msg
        var s = msg
        // Order matters: handle "Executing:" first so its trailing command becomes <command-redacted>
        // BEFORE we attempt URL/extra redaction inside the now-removed body.
        s = EXECUTING_PREFIX.replace(s) { it.groupValues[1] + "<command-redacted>" }
        s = EXTRA_REGEX.replace(s) { "--es ${it.groupValues[1]} <redacted>" }
        s = URL_REGEX.replace(s) { redactUrl(it.value) }
        if (s.length > MAX_LEN) s = s.substring(0, MAX_LEN) + "…"
        return s
    }
}
