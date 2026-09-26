package com.castla.mirror.backup

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Phone-side favorites / recent apps for the car's app grid (source of truth; the car caches). */
object LauncherStore {
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENT = "recent"

    private fun prefs(context: Context) =
        context.getSharedPreferences(BackupPolicy.LAUNCHER_FILE, Context.MODE_PRIVATE)

    fun favorites(context: Context): List<String> = read(prefs(context), KEY_FAVORITES)
    fun recent(context: Context): List<String> = read(prefs(context), KEY_RECENT)

    fun save(context: Context, favorites: List<String>?, recent: List<String>?) {
        val edit = prefs(context).edit()
        favorites?.let {
            edit.putString(KEY_FAVORITES, JSONArray(LauncherLists.sanitize(it, LauncherLists.MAX_FAVORITES)).toString())
        }
        recent?.let {
            edit.putString(KEY_RECENT, JSONArray(LauncherLists.sanitize(it, LauncherLists.MAX_RECENT)).toString())
        }
        edit.apply()
    }

    private fun read(prefs: SharedPreferences, key: String): List<String> = try {
        val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
        List(arr.length()) { arr.optString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Export / import of Castla's settings as one JSON file (see [BackupPolicy] for
 * what is included). Values keep their SharedPreferences type so an import
 * restores exactly what was saved.
 */
object SettingsBackup {

    data class ImportResult(val entries: Int, val includedSecrets: Boolean)

    fun export(context: Context, includeSecrets: Boolean, appVersion: String): String {
        val files = JSONObject()
        for (file in BackupPolicy.FILES) {
            val entries = JSONObject()
            val all = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
            for ((key, value) in all.toSortedMap()) {
                if (!BackupPolicy.isIncluded(file, key, includeSecrets)) continue
                encode(value)?.let { entries.put(key, it) }
            }
            if (entries.length() > 0) files.put(file, entries)
        }
        return JSONObject().apply {
            put("format", BackupPolicy.FORMAT)
            put("version", BackupPolicy.VERSION)
            put("app", appVersion)
            put("exportedAt", System.currentTimeMillis())
            put("includesSecrets", includeSecrets)
            put("prefs", files)
        }.toString(2)
    }

    /** Throws IllegalArgumentException for a file that is not a Castla backup. */
    fun import(context: Context, json: String): ImportResult {
        val root = try { JSONObject(json) } catch (e: Exception) {
            throw IllegalArgumentException("Not a JSON file")
        }
        require(BackupPolicy.canImport(root.optString("format", null), root.optInt("version", -1))) {
            "Not a Castla backup (or made by a newer version)"
        }
        val files = root.optJSONObject("prefs") ?: JSONObject()
        var count = 0
        var secrets = false
        for (file in BackupPolicy.FILES) {
            val entries = files.optJSONObject(file) ?: continue
            val edit = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            for (key in entries.keys()) {
                // Secrets in the file are restored: the user chose to export them.
                if (!BackupPolicy.isIncluded(file, key, includeSecrets = true)) continue
                if (decode(edit, key, entries.optJSONObject(key))) {
                    count++
                    if (file == BackupPolicy.SECURITY_FILE) secrets = true
                }
            }
            edit.commit()
        }
        return ImportResult(count, secrets)
    }

    private fun encode(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun decode(edit: SharedPreferences.Editor, key: String, entry: JSONObject?): Boolean {
        if (entry == null || !entry.has("v")) return false
        when (entry.optString("t")) {
            "b" -> edit.putBoolean(key, entry.optBoolean("v"))
            "i" -> edit.putInt(key, entry.optInt("v"))
            "l" -> edit.putLong(key, entry.optLong("v"))
            "f" -> edit.putFloat(key, entry.optDouble("v").toFloat())
            "s" -> edit.putString(key, entry.optString("v"))
            "ss" -> {
                val arr = entry.optJSONArray("v") ?: return false
                edit.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
            }
            else -> return false
        }
        return true
    }
}
