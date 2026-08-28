package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {

    @Test
    fun `redactUrl strips path query fragment, preserves scheme and host`() {
        assertEquals(
            "https://www.youtube.com",
            DiagnosticSanitizer.redactUrl("https://www.youtube.com/watch?v=abc123#t=10")
        )
        assertEquals(
            "http://192.168.1.5",
            DiagnosticSanitizer.redactUrl("http://192.168.1.5:9090/api/apps?secret=xyz")
        )
    }

    @Test
    fun `redactUrl returns sentinel for empty or malformed input`() {
        assertEquals("<invalid-url>", DiagnosticSanitizer.redactUrl(""))
        assertEquals("<invalid-url>", DiagnosticSanitizer.redactUrl("not a url"))
    }

    @Test
    fun `safeMessage redacts URL inside message`() {
        val msg = "Failed to load https://www.youtube.com/watch?v=secret in browser"
        val sanitized = DiagnosticSanitizer.safeMessage(msg)
        assertTrue("got: $sanitized", sanitized.contains("https://www.youtube.com"))
        assertTrue(!sanitized.contains("secret"))
    }

    @Test
    fun `safeMessage redacts shell command after Executing prefix`() {
        val msg = "Executing: am start -W --display 2 -n com.example/.Activity --es url https://secret.example.com/path"
        val sanitized = DiagnosticSanitizer.safeMessage(msg)
        assertTrue("got: $sanitized", sanitized.contains("Executing:"))
        assertTrue("got: $sanitized", sanitized.contains("<command-redacted>"))
        assertTrue("got: $sanitized", !sanitized.contains("secret.example.com"))
    }

    @Test
    fun `safeMessage redacts intent extras keys preserve key, replaces value`() {
        val msg = "Launching with --es url https://example.com/path/to/page and --es token abcd1234"
        val sanitized = DiagnosticSanitizer.safeMessage(msg)
        assertTrue("got: $sanitized", sanitized.contains("--es url <redacted>"))
        assertTrue("got: $sanitized", sanitized.contains("--es token <redacted>"))
        assertTrue("got: $sanitized", !sanitized.contains("abcd1234"))
    }

    @Test
    fun `safeMessage caps very long messages at 500 chars with ellipsis`() {
        val longMsg = "A".repeat(2000)
        val sanitized = DiagnosticSanitizer.safeMessage(longMsg)
        assertTrue("got length=${sanitized.length}", sanitized.length <= 501)
        assertTrue(sanitized.endsWith("…"))
    }

    @Test
    fun `safeMessage passes through safe input unchanged`() {
        val msg = "VD recreation failed after retry; taskId=42 displayId=2 size=1280x720"
        assertEquals(msg, DiagnosticSanitizer.safeMessage(msg))
    }

    @Test
    fun `safeMessage handles empty string`() {
        assertEquals("", DiagnosticSanitizer.safeMessage(""))
    }

    // ── Host header sanitization (untrusted client input) ──

    @Test
    fun `sanitizeHost strips CR LF and control chars to prevent log-line injection`() {
        val host = "192-168-43-1.sslip.io\r\nI FakeTag: [FAKE_EVENT]\u0000\u001B"
        val sanitized = DiagnosticSanitizer.sanitizeHost(host)
        assertTrue("got: $sanitized", !sanitized.contains("\r"))
        assertTrue("got: $sanitized", !sanitized.contains("\n"))
        assertTrue("got: $sanitized", !sanitized.contains("\u0000"))
        assertTrue("got: $sanitized", sanitized.startsWith("192-168-43-1.sslip.io"))
    }

    @Test
    fun `sanitizeHost caps length and handles null`() {
        val sanitized = DiagnosticSanitizer.sanitizeHost("h".repeat(500))
        assertTrue("got length=${sanitized.length}", sanitized.length <= 129)
        assertEquals("<none>", DiagnosticSanitizer.sanitizeHost(null))
        assertEquals("<none>", DiagnosticSanitizer.sanitizeHost("  "))
    }

    // ── IP masking (logs may be shared on public issues) ──

    @Test
    fun `maskIp masks last octet keeping subnet prefix`() {
        assertEquals("192.168.43.***", DiagnosticSanitizer.maskIp("192.168.43.17"))
        assertEquals("100.64.0.***", DiagnosticSanitizer.maskIp("100.64.0.1"))
    }

    @Test
    fun `maskIp handles non-IPv4 and null input`() {
        assertEquals("<masked>", DiagnosticSanitizer.maskIp("fe80::1"))
        assertEquals("<none>", DiagnosticSanitizer.maskIp(null))
        assertEquals("<none>", DiagnosticSanitizer.maskIp(""))
    }
}
