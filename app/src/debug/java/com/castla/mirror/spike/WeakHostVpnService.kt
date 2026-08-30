package com.castla.mirror.spike

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * DEBUG-ONLY weak-host synthesis experiment (issue #51 Phase 1).
 *
 * Assigns the synthetic address [WeakHostSpike.SYNTH_ADDR] to a TUN so a hotspot client
 * (the car) can reach the EXISTING MirrorServer (0.0.0.0:9090) via weak-host delivery —
 * no relay, no packet capture. It deliberately does NOT route the synthetic address into
 * the tun: an inbound car packet must be delivered to the local server socket, not swallowed
 * by the fd. A throwaway /32 route only satisfies establish()'s route requirement.
 *
 * Gates (abort, not warn): ADDRESS_CONFLICT if the address already exists (e.g. CLAT), and
 * refuse if another VPN is active (Android allows one). Manual start/stop only, never auto.
 * Declared only in the debug manifest; never in release.
 *
 * ponytail: no startForeground/FGS type wired up — the active VPN keeps the service alive and
 * the system shows its own VPN status icon. Proper FGS plumbing is a Phase 6 production concern.
 */
class WeakHostVpnService : VpnService() {

    private var iface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra(EXTRA_ACTION) == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (iface != null) return START_NOT_STICKY // already running

        // Gate 1 — ADDRESS_CONFLICT (defense-in-depth; the Activity checks first).
        if (WeakHostSpike.addressConflict(localIpv4())) {
            Log.w(TAG, "[WEAKHOST_ADDRESS_CONFLICT] ${WeakHostSpike.SYNTH_ADDR} already present; aborting")
            stopSelf(); return START_NOT_STICKY
        }
        // Gate 2 — another VPN active (Android allows only one).
        if (anotherVpnActive(this)) {
            Log.w(TAG, "[WEAKHOST_VPN_BUSY] another VPN is active; refusing")
            stopSelf(); return START_NOT_STICKY
        }

        val pfd = try {
            Builder()
                .setSession("weakhost-spike")
                .addAddress(WeakHostSpike.SYNTH_ADDR, 32) // synthesize the address locally
                .addRoute(DUMMY_ROUTE, 32)                // throwaway; satisfies establish(), NOT the synthetic addr
                .setMtu(1500)
                .establish()
        } catch (e: Throwable) {
            Log.w(TAG, "[WEAKHOST_ESTABLISH_FAILED] ${e.javaClass.simpleName}: ${e.message}"); null
        }
        if (pfd == null) {
            Log.w(TAG, "[WEAKHOST_ESTABLISH_FAILED] establish returned null")
            stopSelf(); return START_NOT_STICKY
        }
        iface = pfd
        isRunning = true
        Log.i(TAG, "[WEAKHOST_READY] synthesized ${WeakHostSpike.SYNTH_ADDR}/32 — " +
            "try http://${WeakHostSpike.SYNTH_ADDR}:9090 from the car (do NOT probe it from this phone)")
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "[WEAKHOST_REVOKED] VPN preempted or revoked")
        stopSelf()
    }

    override fun onDestroy() {
        try { iface?.close() } catch (_: Throwable) {}
        iface = null
        isRunning = false
        Log.i(TAG, "[WEAKHOST_STOPPED]")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WeakHostSpike"

        /** Whether the synthesis TUN is currently up — lets the Activity act as a toggle. */
        @Volatile var isRunning = false
            private set
        private const val DUMMY_ROUTE = "198.51.100.1" // RFC 5737 TEST-NET-2, unused; never the synthetic addr
        const val EXTRA_ACTION = "action"
        const val ACTION_STOP = "stop"

        /** IPv4 addresses on every up, non-loopback interface. Shared by the Activity gate. */
        fun localIpv4(): List<String> = buildList {
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (a in nif.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress) a.hostAddress?.let { add(it) }
                    }
                }
            } catch (_: Throwable) { /* best effort */ }
        }

        fun anotherVpnActive(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            return cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }
    }
}
