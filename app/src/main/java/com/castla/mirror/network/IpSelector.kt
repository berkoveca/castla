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
            // Everything else (likely mobile data) — low priority
            else -> 1
        }
    }

    fun select(candidates: List<IpCandidate>): IpCandidate? = candidates.maxByOrNull { it.priority }

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
