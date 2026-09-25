package com.castla.mirror.diagnostics

/**
 * Decides which cloudflared stderr lines are worth persisting to the on-disk
 * log. cloudflared is chatty at INF level; persisting everything would rotate
 * the useful app history out of the 1 MB log within minutes on a flapping
 * connection. We keep every WRN/ERR/FTL/PNC line, the handful of INF lines that
 * mark connection lifecycle, and Go panic headers.
 */
object CloudflaredLogFilter {

    private val LEVEL = Regex("""(?:^|\s)(TRC|DBG|INF|WRN|ERR|FTL|PNC)\s""")
    private val ALWAYS_LEVELS = setOf("WRN", "ERR", "FTL", "PNC")
    private val KEY_INFO = listOf(
        "Registered tunnel connection",
        "Unregistered tunnel connection",
        "Starting tunnel",
        "Version ",
        "GOOS:",
        "Initial protocol",
        "Switching to fallback protocol",
        "Retrying connection",
        "Connection terminated",
        "Lost connection",
        "Tunnel server stopped",
        "Initiating graceful shutdown",
        "Updated to new configuration"
    )
    private val PANIC_PREFIXES = listOf("panic:", "fatal error:", "runtime: ")

    private val TOKEN_FLAG = Regex("""(--token)(\s+|=)\S+""")
    private val TOKEN_KV = Regex("""(?i)(token[:=])\s*[^\s\]]+""")
    private val LONG_BLOB = Regex("""[A-Za-z0-9+/_=-]{64,}""")

    /** Level token (e.g. "ERR") of a zerolog console line, or null. */
    fun levelOf(line: String): String? = LEVEL.find(line)?.groupValues?.get(1)

    fun isErrorLevel(line: String): Boolean = levelOf(line).let { it == "ERR" || it == "FTL" || it == "PNC" }

    fun shouldPersist(line: String): Boolean {
        if (line.isBlank()) return false
        val trimmed = line.trimStart()
        if (PANIC_PREFIXES.any { trimmed.startsWith(it) }) return true
        val level = levelOf(line) ?: return false
        if (level in ALWAYS_LEVELS) return true
        if (level != "INF") return false
        return KEY_INFO.any { line.contains(it) }
    }

    /** Strips the connector token (and any other long credential-like blob). */
    fun redact(line: String): String {
        var s = TOKEN_FLAG.replace(line) { "${it.groupValues[1]} <redacted>" }
        s = TOKEN_KV.replace(s) { "${it.groupValues[1]}<redacted>" }
        s = LONG_BLOB.replace(s, "<redacted>")
        return s
    }
}

/**
 * Token bucket: allows a burst of [capacity] persisted lines, then one more per
 * [refillIntervalMs]. Lines rejected while empty are counted so the next
 * accepted line can say how many were skipped. Not thread-safe — callers own
 * one instance per reader thread (or synchronize).
 */
class PersistBudget(private val capacity: Int, private val refillIntervalMs: Long) {
    private var tokens = capacity
    private var lastRefillMs: Long? = null
    private var suppressed = 0

    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastRefillMs
        if (last == null) {
            lastRefillMs = nowMs
        } else if (nowMs > last && refillIntervalMs > 0) {
            val gained = ((nowMs - last) / refillIntervalMs).toInt()
            if (gained > 0) {
                tokens = (tokens + gained).coerceAtMost(capacity)
                lastRefillMs = last + gained * refillIntervalMs
            }
        }
        if (tokens > 0) {
            tokens--
            return true
        }
        suppressed++
        return false
    }

    fun takeSuppressedCount(): Int = suppressed.also { suppressed = 0 }
}
