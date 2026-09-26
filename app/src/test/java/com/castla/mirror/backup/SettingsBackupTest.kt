package com.castla.mirror.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {

    private lateinit var context: Context

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        BackupPolicy.FILES.forEach { prefs(it).edit().clear().commit() }
        prefs("castla_settings").edit()
            .putString("max_resolution", "RES_960").putInt("fps", 30).putBoolean("keep_awake", false).commit()
        LauncherStore.save(context, listOf("com.waze", "com.spotify.music"), listOf("com.waze"))
        prefs("tunnel_security").edit()
            .putString("auth_password", "secret123").putString("named_token", "tok")
            .putString("auth_secret", "never-export").commit()
    }

    private fun wipe() = BackupPolicy.FILES.forEach { prefs(it).edit().clear().commit() }

    @Test
    fun `round trip restores settings and favorites with their types`() {
        val json = SettingsBackup.export(context, includeSecrets = false, appVersion = "t")
        wipe()
        val result = SettingsBackup.import(context, json)
        assertEquals("RES_960", prefs("castla_settings").getString("max_resolution", null))
        assertEquals(30, prefs("castla_settings").getInt("fps", 0))
        assertFalse(prefs("castla_settings").getBoolean("keep_awake", true))
        assertEquals(listOf("com.waze", "com.spotify.music"), LauncherStore.favorites(context))
        assertEquals(listOf("com.waze"), LauncherStore.recent(context))
        assertFalse(result.includedSecrets)
    }

    @Test
    fun `secrets only when chosen and the session secret never`() {
        val without = JSONObject(SettingsBackup.export(context, includeSecrets = false, appVersion = "t"))
        assertFalse(without.getJSONObject("prefs").has("tunnel_security"))

        val withSecrets = SettingsBackup.export(context, includeSecrets = true, appVersion = "t")
        assertFalse(withSecrets.contains("never-export"))
        wipe()
        val result = SettingsBackup.import(context, withSecrets)
        assertTrue(result.includedSecrets)
        assertEquals("secret123", prefs("tunnel_security").getString("auth_password", null))
        assertEquals("tok", prefs("tunnel_security").getString("named_token", null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file that is not a castla backup`() {
        SettingsBackup.import(context, """{"format":"other","version":1}""")
    }
}
