package com.castla.mirror.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyTest {

    @Test
    fun `stream settings and launcher lists are always included`() {
        assertTrue(BackupPolicy.isIncluded("castla_settings", "max_resolution", includeSecrets = false))
        assertTrue(BackupPolicy.isIncluded("castla_settings", "keep_awake", includeSecrets = false))
        assertTrue(BackupPolicy.isIncluded("castla_launcher", "favorites", includeSecrets = false))
    }

    @Test
    fun `password and tunnel token only when asked`() {
        assertFalse(BackupPolicy.isIncluded("tunnel_security", "named_token", includeSecrets = false))
        assertFalse(BackupPolicy.isIncluded("tunnel_security", "auth_password", includeSecrets = false))
        assertTrue(BackupPolicy.isIncluded("tunnel_security", "named_token", includeSecrets = true))
        assertTrue(BackupPolicy.isIncluded("tunnel_security", "auth_password", includeSecrets = true))
    }

    @Test
    fun `session secret and unknown files are never included`() {
        assertFalse(BackupPolicy.isIncluded("tunnel_security", "auth_secret", includeSecrets = true))
        assertFalse(BackupPolicy.isIncluded("shizuku_setup", "anything", includeSecrets = true))
        assertFalse(BackupPolicy.isIncluded("diag_breadcrumbs", "boot", includeSecrets = true))
    }

    @Test
    fun `accepts only castla backups of a known version`() {
        assertTrue(BackupPolicy.canImport("castla-backup", 1))
        assertFalse(BackupPolicy.canImport("castla-backup", 99))
        assertFalse(BackupPolicy.canImport("something-else", 1))
        assertFalse(BackupPolicy.canImport(null, 1))
    }

    @Test
    fun `launcher lists keep valid unique package names, capped`() {
        assertEquals(
            listOf("com.waze", "com.spotify.music"),
            LauncherLists.sanitize(listOf(" com.waze", "com.spotify.music", "com.waze", "not a pkg", "../etc", ""), 10)
        )
        assertEquals(2, LauncherLists.sanitize(listOf("a.b", "c.d", "e.f"), 2).size)
    }
}
