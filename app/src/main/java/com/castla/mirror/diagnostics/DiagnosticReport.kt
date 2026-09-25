package com.castla.mirror.diagnostics

/**
 * Pure builder for the "Copy Recent" clipboard payload: a short header of
 * labelled sections (device, health, reboot check, tunnel, …) followed by as
 * much of the newest on-disk log as fits in the size budget.
 */
object DiagnosticReport {

    private const val LOG_HEADER = "== Recent log (oldest → newest) =="

    fun build(
        title: String,
        sections: List<Pair<String, List<String>>>,
        logText: String,
        maxChars: Int
    ): String {
        val header = buildString {
            append(title).append('\n')
            for ((name, lines) in sections) {
                append('\n').append("== ").append(name).append(" ==").append('\n')
                if (lines.isEmpty()) append("(none)\n")
                for (l in lines) append(l).append('\n')
            }
            append('\n').append(LOG_HEADER).append('\n')
        }
        // Never let the header crowd out the log entirely.
        val headerBudget = maxChars / 2
        val cappedHeader = if (header.length <= headerBudget) header else {
            header.substring(0, (headerBudget - LOG_HEADER.length - 20).coerceAtLeast(0)) +
                "\n…(header truncated)\n" + LOG_HEADER + "\n"
        }
        val logBudget = (maxChars - cappedHeader.length).coerceAtLeast(0)
        val tail = if (logText.isEmpty()) "(no log entries)\n" else tailAtLineBoundary(logText, logBudget)
        return (cappedHeader + tail).take(maxChars)
    }

    /**
     * Last [maxChars] characters of [text], starting at a line boundary so the
     * first line is never a fragment. Falls back to the raw tail when the
     * window contains no newline (a single huge line).
     */
    fun tailAtLineBoundary(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val raw = text.substring(text.length - maxChars)
        // If the cut landed exactly after a newline, raw already starts on a full line.
        if (text[text.length - maxChars - 1] == '\n') return raw
        val nl = raw.indexOf('\n')
        return if (nl in 0 until raw.length - 1) raw.substring(nl + 1) else raw
    }
}
