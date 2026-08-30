package com.castla.mirror.network

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

data class IpCandidate(val ip: String, val iface: String, val priority: Int)

/**
 * Pure IPv4 advertise-address selection: hotspot gateway > hotspot interface >
 * wifi > ethernet > everything else (mobile data). Extracted from NetworkMonitor
 * so priority logic is unit-testable and shared with session-start diagnostics.
 */
object IpSelector {

    private const val TAG = "IpSelector"

    fun priorityOf(ifaceName: String, ip: String): Int {
        val name = ifaceName.lowercase()
        return when {
            // Hotspot gateway IPs — highest priority (reachable by hotspot clients)
            ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.") -> 20
            // Other private IPs on known hotspot interfaces
            name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 15
            // wlan with private IP (WiFi connected to router)
            name.startsWith("wlan") && ip.startsWith("192.168.") -> 10
            name.startsWith("wlan") && ip.startsWith("10.") -> 5
            name.startsWith("eth") -> 3
            // CLAT/464XLAT dummy address (RFC 7600, 192.0.0.0/24 special-use). On IPv6-only
            // carriers the phone already holds a 192.0.0.x locally; a car on the hotspot can
            // reach it via weak-host delivery (issue #51). Lift it above generic cellular so it
            // is visible in the candidate list, but keep it BELOW every WiFi/hotspot tier so a
            // working private-IP setup's first-try URL is never regressed. ReachableIp promotes
            // whatever actually gets first contact, so this only nudges the initial guess.
            ip.startsWith("192.0.0.") -> 4
            // Everything else (likely mobile data) — low priority
            else -> 1
        }
    }

    /**
     * [preferred] is an address a browser actually reached us on ([ReachableIp]);
     * it wins whenever it is still live, because measurement beats the priority
     * guess. Falls back to the highest priority before any successful contact.
     */
    fun select(candidates: List<IpCandidate>, preferred: String? = null): IpCandidate? =
        candidates.firstOrNull { it.ip == preferred } ?: candidates.maxByOrNull { it.priority }

    /**
     * The other candidate IPs, highest priority first, deduped.
     *
     * No single priority is right on every device: a tethered client can sit on
     * the hotspot yet be unable to reach the hotspot's own address, while another
     * local address of the same phone works (issue #51). Callers offer the whole
     * list rather than trusting one guess.
     */
    fun alternativesTo(selected: String, candidates: List<IpCandidate>): List<String> =
        candidates.sortedByDescending { it.priority }
            .map { it.ip }
            .distinct()
            .filter { it != selected }

    /**
     * Advertised URL for [ip] — always the raw IP, never a hostname.
     *
     * Hostname forms (the old sslip.io wrapping) are unreachable on IPv6-only
     * carriers: with 464XLAT/NAT64 the network's DNS64 synthesizes the A record
     * into a 64:ff9b::/96 address, which routes to the carrier's NAT64 gateway
     * instead of this phone, so the browser never reaches the server. Measured
     * on issue #51 — 192-0-0-8.sslip.io resolved to 64:ff9b::c000:8 and timed
     * out while http://192.0.0.8 worked from the same client.
     */
    fun advertiseUrl(ip: String, port: Int): String = "http://$ip:$port"

    /** Enumerates live interfaces into candidates. Never yields 0.0.0.0 or loopback. */
    fun scan(): List<IpCandidate> {
        val candidates = mutableListOf<IpCandidate>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return candidates
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        if (ip == "0.0.0.0") continue
                        candidates.add(IpCandidate(ip, iface.name, priorityOf(iface.name, ip)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Interface scan failed", e)
        }
        return candidates
    }
}
