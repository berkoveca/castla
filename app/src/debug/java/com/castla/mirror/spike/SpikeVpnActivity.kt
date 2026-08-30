package com.castla.mirror.spike

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

/**
 * DEBUG-ONLY, smallest possible consent entry point for [SpikeVpnService]
 * (issue #51, Phase 0.5a). Requests the system VPN consent dialog, then starts
 * the spike and finishes. Launch with:
 *   adb shell am start -n com.castla.mirror.debug/com.castla.mirror.spike.SpikeVpnActivity
 */
class SpikeVpnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQ)
        } else {
            onActivityResult(REQ, RESULT_OK, null) // already granted
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK) {
            val svc = Intent(this, SpikeVpnService::class.java)
            // Forward `--el timeout_ms <ms>` from `am start` so the window can be tuned.
            if (intent.hasExtra(SpikeVpnService.EXTRA_TIMEOUT_MS)) {
                svc.putExtra(
                    SpikeVpnService.EXTRA_TIMEOUT_MS,
                    intent.getLongExtra(SpikeVpnService.EXTRA_TIMEOUT_MS, 0L)
                )
            }
            startService(svc)
            Log.i(TAG, "[SPIKE_STARTED] service launched")
        } else {
            Log.w(TAG, "[SPIKE_CONSENT_DENIED]")
        }
        finish()
    }

    companion object {
        private const val TAG = "SynSpike"
        private const val REQ = 0x5117
    }
}
