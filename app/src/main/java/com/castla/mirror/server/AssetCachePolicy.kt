package com.castla.mirror.server

/**
 * Keeps the car's browser (and the Cloudflare edge, which caches .js/.css by
 * default) from running stale page code after an app update. A stale main.js
 * silently undoes client-side fixes, e.g. the app-launch timeout.
 */
object AssetCachePolicy {

    const val NO_STORE = "no-store, no-cache, must-revalidate, max-age=0"

    fun isNoStore(path: String): Boolean =
        path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css")

    private val LOCAL_ASSET = Regex("""(src|href)="((?:js|css)/[^"?]+\.(?:js|css))"""")

    /** Appends `?v=[version]` to local script/stylesheet URLs so each build gets fresh URLs. */
    fun versionUrls(html: String, version: String): String =
        LOCAL_ASSET.replace(html) { m -> """${m.groupValues[1]}="${m.groupValues[2]}?v=$version"""" }
}
