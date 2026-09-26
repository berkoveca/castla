package com.castla.mirror.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAttemptsTest {

    private val log = listOf(
        "2026-09-26T16:14:11.841 I MirrorDiag: [PAGE_LOAD] +1ms src=1.2.3.*** via=tunnel country=NL password=set session=none (login page shown) ua=Mac (t=x)",
        "2026-09-26T16:14:20.000 W Auth: LOGIN FAILED (wrong password) src=1.2.3.*** via=tunnel country=NL ua=Mac (t=NanoHttpd Request Processor (#3))",
        "2026-09-26T16:14:30.000 I Health: something else (t=y)",
        "2026-09-26T16:15:00.000 W Auth: LOGIN OK src=5.6.7.*** via=tunnel country=BG ua=Tesla (t=z)",
        "2026-09-26T16:15:01.000 I Auth: live connection accepted (logged in) src=5.6.7.*** via=tunnel country=BG ua=Tesla (t=z)"
    )

    @Test
    fun `keeps only auth and page-load lines, as plain time and message`() {
        val out = AuthAttempts.extract(log)
        assertEquals(4, out.size)
        assertEquals("16:14:11  page opened: src=1.2.3.*** via=tunnel country=NL password=set session=none (login page shown) ua=Mac", out[0])
        assertEquals("16:14:20  LOGIN FAILED (wrong password) src=1.2.3.*** via=tunnel country=NL ua=Mac", out[1])
        assertTrue(out[3].startsWith("16:15:01  live connection accepted"))
    }

    @Test
    fun `keeps the newest entries when there are too many`() {
        val many = (0 until 200).map { "2026-09-26T10:00:${(it % 60).toString().padStart(2, '0')}.000 W Auth: LOGIN FAILED n=$it (t=a)" }
        val out = AuthAttempts.extract(many, max = 50)
        assertEquals(50, out.size)
        assertTrue(out.last().contains("n=199"))
    }

    @Test
    fun `counts results for the summary`() {
        val s = AuthAttempts.summary(AuthAttempts.extract(log))
        assertTrue(s, s.contains("1 failed") && s.contains("1 successful") && s.contains("1 page opens"))
    }
}
