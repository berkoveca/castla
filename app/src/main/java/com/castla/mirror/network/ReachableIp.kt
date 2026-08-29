package com.castla.mirror.network

import android.content.Context

/**
 * Remembers the address a browser actually reached this phone on, taken from
 * the first-contact `Host` header and preferred over the priority guess next
 * time.
 *
 * Which local address a client can reach is device- and carrier-dependent and
 * cannot be derived on this side: a tethered client may sit on the hotspot yet
 * be unable to reach the hotspot's own address while another local address of
 * the same phone works (issue #51). Two opposite guesses have already shipped
 * and failed, so the app measures instead of guessing a third time.
 */
object ReachableIp {

    private const val PREFS = "reachable_ip"
    private const val KEY_LAST = "last_ip"

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * The IPv4 literal a `Host` header names, or null for anything else.
     *
     * Hostnames are rejected on purpose: only a literal tells us which of our
     * addresses the client reached. The header is remote input, so nothing but
     * a well-formed literal is ever stored.
     */
    fun ipFromHostHeader(host: String?): String? {
        val candidate = host?.trim()?.substringBefore(':') ?: return null
        val m = IPV4.matchEntire(candidate) ?: return null
        return if (m.groupValues.drop(1).all { it.toInt() <= 255 }) candidate else null
    }

    /** No-op unless [host] carries a usable IPv4 literal. */
    fun remember(context: Context, host: String?) {
        val ip = ipFromHostHeader(host) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, ip).apply()
    }

    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)
}
