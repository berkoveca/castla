package com.castla.mirror.diagnostics

/**
 * Plain-text list of who tried to get in: page opens, logins (ok / failed /
 * blocked / refused) and live connections, taken from the persistent log.
 * Typed passwords are never logged — a mistyped own password is usually
 * almost the real one, and this list is copied to the clipboard.
 */
object AuthAttempts {

    private val LINE = Regex("""^\d{4}-\d{2}-\d{2}T(\d{2}:\d{2}:\d{2})\.\d+ \w (\S+): (.*?)(?: \(t=.*\))?$""")

    fun extract(lines: List<String>, max: Int = 60): List<String> =
        lines.mapNotNull { raw ->
            val m = LINE.find(raw) ?: return@mapNotNull null
            val (time, tag, msg) = m.destructured
            when {
                tag == "Auth" -> "$time  $msg"
                tag == "MirrorDiag" && msg.startsWith("[PAGE_LOAD]") ->
                    "$time  page opened: " + msg.substringAfter("] ").replace(Regex("""^\+\d+ms\s*"""), "")
                else -> null
            }
        }.takeLast(max)

    fun summary(entries: List<String>): String {
        val failed = entries.count { "LOGIN FAILED" in it || "LOGIN REFUSED" in it }
        val ok = entries.count { "LOGIN OK" in it }
        val blocked = entries.count { "LOGIN BLOCKED" in it }
        val rejected = entries.count { "REJECTED" in it }
        val opens = entries.count { "page opened" in it }
        return "$ok successful, $failed failed, $blocked blocked (lockout), $rejected rejected connections, $opens page opens"
    }
}
