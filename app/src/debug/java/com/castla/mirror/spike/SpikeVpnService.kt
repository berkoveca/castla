package com.castla.mirror.spike

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.castla.mirror.diagnostics.DiagnosticSanitizer
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * DEBUG-ONLY packet-reachability spike (issue #51, Phase 0.5a).
 *
 * Stands up a minimal TUN with a single virtual /32 address and watches for one
 * inbound TCP SYN. It deliberately does NOT relay, NAT, or route real traffic and
 * never installs a full-tunnel default route. Purpose: empirically determine
 * whether a *non-privileged* VpnService can even observe the vehicle's SYN before
 * anyone invests in an HTTP relay.
 *
 * Gate (do not skip): SYN seen -> NON_PRIVILEGED_SYN_OBSERVED and stop. No SYN
 * before timeout -> NON_PRIVILEGED_NOT_OBSERVED and stop. Observing a SYN alone is
 * NOT proof the approach works — it only unlocks designing the next (relay) step.
 *
 * Never promoted to production; declared only in the debug manifest.
 */
class SpikeVpnService : VpnService() {

    @Volatile private var running = false
    @Volatile private var observed = false
    private var iface: ParcelFileDescriptor? = null

    private var timeoutMs = DEFAULT_TIMEOUT_MS

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-run safe: a completed window may have left a stale session on this reused
        // instance. Tear it down first so every launch establishes a fresh TUN.
        if (running) teardown()
        running = true
        observed = false
        timeoutMs = intent?.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        start()
        return START_NOT_STICKY
    }

    private fun start() {
        val pfd = Builder()
            .setSession("syn-spike")
            .addAddress(VIRTUAL_ADDR, 32)  // single virtual address
            .addRoute(VIRTUAL_ADDR, 32)    // /32 only — never a full-tunnel default route
            .setMtu(MTU)
            .establish()
        if (pfd == null) {
            Log.w(TAG, "[SPIKE_TUN_READY] establish=null (consent missing?) $GATE_NOT_OBSERVED")
            finish(null); return
        }
        iface = pfd
        Log.i(TAG, "[SPIKE_TUN_READY] addr=${DiagnosticSanitizer.maskIp(VIRTUAL_ADDR)}/32 mtu=$MTU port=$SPIKE_PORT")

        thread(name = "syn-spike-timeout", isDaemon = true) {
            Thread.sleep(timeoutMs)
            if (running && !observed) {
                finish("[SPIKE_TIMEOUT_NO_SYN] $GATE_NOT_OBSERVED after=${timeoutMs}ms")
            }
        }

        thread(name = "syn-spike-read", isDaemon = true) {
            val buf = ByteArray(MTU)
            FileInputStream(pfd.fileDescriptor).use { input ->
                while (running) {
                    val n = try { input.read(buf) } catch (_: Throwable) { break }
                    if (n <= 0) continue
                    val r = TunSynParser.parse(buf, n)
                    if (r.isSyn) {
                        observed = true
                        finish(
                            "[SPIKE_SYN_OBSERVED] src=${DiagnosticSanitizer.maskIp(r.srcIp)} " +
                                "dst=${DiagnosticSanitizer.maskIp(r.dstIp)} $GATE_SYN_OBSERVED"
                        )
                        break
                    }
                }
            }
        }
    }

    /** Single teardown path — closing the fd is what actually drops tun0 (do not rely on onDestroy). */
    private fun teardown() {
        running = false
        try { iface?.close() } catch (_: Throwable) {}
        iface = null
    }

    /** Log the terminal result (if any), tear down the TUN immediately, and stop the service. */
    @Synchronized private fun finish(logLine: String?) {
        if (!running && iface == null) return // already finished
        if (logLine != null) Log.i(TAG, logLine)
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SynSpike"

        // RFC 5737 TEST-NET-2: reserved for documentation, guaranteed never a real host.
        // Deliberately NOT 192.0.0.8 (that address was only ever bound on one maintainer
        // device and is not a common address — hardcoding it is forbidden by the spike spec).
        const val VIRTUAL_ADDR = "198.51.100.2"
        const val SPIKE_PORT = 9090

        /** Observation window; override via `--el timeout_ms <ms>` on the start intent. */
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        private const val DEFAULT_TIMEOUT_MS = 180_000L // 3 min — time to reach the car

        private const val MTU = 1500
        private const val GATE_SYN_OBSERVED = "gate=NON_PRIVILEGED_SYN_OBSERVED"
        private const val GATE_NOT_OBSERVED = "gate=NON_PRIVILEGED_NOT_OBSERVED"
        // ponytail: relies on the foreground grace of the launching Activity to start the
        // service; no FGS type wired up. Fine for a short debug observation window, revisit if
        // it needs to survive long backgrounding.
    }
}
