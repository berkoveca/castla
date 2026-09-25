package com.castla.mirror.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetCachePolicyTest {

    @Test
    fun `page code is never cached`() {
        listOf("index.html", "js/main.js", "css/player.css").forEach {
            assertTrue(it, AssetCachePolicy.isNoStore(it))
        }
    }

    @Test
    fun `images may be cached`() {
        assertFalse(AssetCachePolicy.isNoStore("img/logo.png"))
    }

    @Test
    fun `local script and stylesheet urls get the version`() {
        val html = """<link rel="stylesheet" href="css/player.css">
            |<script src="js/main.js"></script>""".trimMargin()
        val out = AssetCachePolicy.versionUrls(html, "42")
        assertTrue(out, out.contains("""href="css/player.css?v=42""""))
        assertTrue(out, out.contains("""src="js/main.js?v=42""""))
    }

    @Test
    fun `external and already versioned urls are left alone`() {
        val html = """<script src="https://cdn.example/x.js"></script><script src="js/a.js?v=1"></script>"""
        assertEquals(html, AssetCachePolicy.versionUrls(html, "42"))
    }
}
