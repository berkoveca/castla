package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {

    @Test
    fun `tail returns everything when it fits`() {
        assertEquals("a\nb\n", DiagnosticReport.tailAtLineBoundary("a\nb\n", 100))
    }

    @Test
    fun `tail starts at a full line`() {
        // Last 6 chars are "b\nccc\n" — the partial "b" line must be dropped.
        assertEquals("ccc\n", DiagnosticReport.tailAtLineBoundary("aaa\nbbb\nccc\n", 6))
    }

    @Test
    fun `tail of a single huge line falls back to raw tail`() {
        val s = "x".repeat(50)
        assertEquals("x".repeat(10), DiagnosticReport.tailAtLineBoundary(s, 10))
    }

    @Test
    fun `tail with non-positive budget is empty`() {
        assertEquals("", DiagnosticReport.tailAtLineBoundary("abc", 0))
    }

    @Test
    fun `build includes title sections and log tail`() {
        val out = DiagnosticReport.build(
            title = "Castla diagnostic report",
            sections = listOf(
                "Device" to listOf("model=Pixel", "sdk=35"),
                "Tunnel" to listOf("state=running")
            ),
            logText = "line1\nline2\n",
            maxChars = 10_000
        )
        assertTrue(out, out.startsWith("Castla diagnostic report"))
        assertTrue(out, out.contains("== Device =="))
        assertTrue(out, out.contains("model=Pixel"))
        assertTrue(out, out.contains("== Tunnel =="))
        assertTrue(out, out.contains("== Recent log"))
        assertTrue(out, out.trimEnd().endsWith("line2"))
    }

    @Test
    fun `build respects the size budget and keeps the newest log lines`() {
        val log = (1..2_000).joinToString("\n") { "2026-09-25T10:00:00.000 I Tag: message number $it" } + "\n"
        val out = DiagnosticReport.build(
            title = "T",
            sections = listOf("S" to listOf("k=v")),
            logText = log,
            maxChars = 2_000
        )
        assertTrue("len=${out.length}", out.length <= 2_000)
        assertTrue(out, out.contains("k=v"))
        assertTrue(out, out.contains("message number 2000"))
    }

    @Test
    fun `oversized header is capped so the log tail still fits`() {
        val hugeSection = List(500) { "header line $it ${"y".repeat(40)}" }
        val out = DiagnosticReport.build(
            title = "T",
            sections = listOf("Big" to hugeSection),
            logText = "the-last-log-line\n",
            maxChars = 4_000
        )
        assertTrue("len=${out.length}", out.length <= 4_000)
        assertTrue(out, out.contains("the-last-log-line"))
    }

    @Test
    fun `empty log is reported explicitly`() {
        val out = DiagnosticReport.build("T", emptyList(), "", 1_000)
        assertTrue(out, out.contains("(no log entries)"))
    }
}
