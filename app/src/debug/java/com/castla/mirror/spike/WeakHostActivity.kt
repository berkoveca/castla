package com.castla.mirror.spike

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * DEBUG-ONLY manual toggle for [WeakHostVpnService] (issue #51 Phase 1).
 *
 * Tap the "Castla WeakHost" launcher icon to toggle: first tap requests VPN consent and
 * synthesizes 192.0.0.8; next tap turns it off. A Toast reports the result so testers who
 * don't use adb (e.g. issue reporters) can drive it. adb also works:
 *   adb shell am start -n com.castla.mirror.debug/com.castla.mirror.spike.WeakHostActivity
 *   adb shell am start -n ... WeakHostActivity --es action stop
 *
 * Gates (ADDRESS_CONFLICT, active-VPN) run BEFORE consent, so a phone that already has the
 * address, or has another VPN, never even shows the consent dialog. Never auto-started.
 */
class WeakHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stopRequested = intent.getStringExtra("action") == WeakHostVpnService.ACTION_STOP
        if (stopRequested || WeakHostVpnService.isRunning) {
            stopService()
            toast("WeakHost OFF")
            Log.i(TAG, "[WEAKHOST_STOP_REQUESTED]")
            finish(); return
        }

        // Gate 1 — ADDRESS_CONFLICT: never synthesize an address the phone already has.
        if (WeakHostSpike.addressConflict(WeakHostVpnService.localIpv4())) {
            toast("${WeakHostSpike.SYNTH_ADDR} already exists on this phone — not needed")
            Log.w(TAG, "[WEAKHOST_ADDRESS_CONFLICT] ${WeakHostSpike.SYNTH_ADDR} already present; aborting")
            finish(); return
        }
        // Gate 2 — refuse if another VPN is active (Android allows one).
        if (WeakHostVpnService.anotherVpnActive(this)) {
            toast("Turn off your other VPN first")
            Log.w(TAG, "[WEAKHOST_VPN_BUSY] another VPN is active; refusing")
            finish(); return
        }

        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ) else onActivityResult(REQ, RESULT_OK, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK) {
            startService(Intent(this, WeakHostVpnService::class.java))
            toast("WeakHost ON — look for a 192.x URL, then try it in the car")
            Log.i(TAG, "[WEAKHOST_STARTED]")
        } else {
            toast("VPN consent denied")
            Log.w(TAG, "[WEAKHOST_CONSENT_DENIED]")
        }
        finish()
    }

    private fun stopService() {
        startService(
            Intent(this, WeakHostVpnService::class.java)
                .putExtra(WeakHostVpnService.EXTRA_ACTION, WeakHostVpnService.ACTION_STOP)
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "WeakHostSpike"
        private const val REQ = 0x5118
    }
}
