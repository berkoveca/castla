package com.castla.mirror.debughook

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.castla.mirror.spike.WeakHostSpike
import com.castla.mirror.spike.WeakHostVpnService

/**
 * DEBUG build only (issue #51 Phase 1). Renders a Settings toggle that starts/stops the
 * weak-host synthesis experiment. Lives in the debug source set so the release build gets
 * the no-op twin in src/release and never references the VpnService.
 */
@Composable
fun WeakHostToggle() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(WeakHostVpnService.isRunning) }

    fun start() {
        context.startService(Intent(context, WeakHostVpnService::class.java))
        running = true
        toast(context, "WeakHost ON — look for a 192.x URL, then try it in the car")
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) start()
        else toast(context, "VPN consent denied")
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("WeakHost ${WeakHostSpike.SYNTH_ADDR} (debug)", color = Color.White)
                Text(
                    "Synthesize ${WeakHostSpike.SYNTH_ADDR} locally to test car reachability (issue #51)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            Switch(
                checked = running,
                onCheckedChange = { want ->
                    if (!want) {
                        context.startService(
                            Intent(context, WeakHostVpnService::class.java)
                                .putExtra(WeakHostVpnService.EXTRA_ACTION, WeakHostVpnService.ACTION_STOP)
                        )
                        running = false
                        toast(context, "WeakHost OFF")
                        return@Switch
                    }
                    when {
                        WeakHostSpike.addressConflict(WeakHostVpnService.localIpv4()) ->
                            toast(context, "${WeakHostSpike.SYNTH_ADDR} already exists on this phone — not needed")
                        WeakHostVpnService.anotherVpnActive(context) ->
                            toast(context, "Turn off your other VPN first")
                        else -> {
                            val intent = VpnService.prepare(context)
                            if (intent != null) consent.launch(intent) else start()
                        }
                    }
                }
            )
        }
    }
}

private fun toast(context: android.content.Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
