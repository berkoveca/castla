package com.castla.mirror.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class NetworkState {
    object Disconnected : NetworkState()
    data class Connected(val ip: String) : NetworkState()
}

class NetworkMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Disconnected)
    val state: StateFlow<NetworkState> = _state

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Every callback rescans all interfaces rather than trusting the single
    // network that changed: losing cellular must not report Disconnected while
    // the hotspot interface is still up (issue #51).
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { refresh() }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Initial state — also covers hotspot mode where callbacks may not fire
        refresh()
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    /**
     * Rescans interfaces and re-emits state. Call explicitly after hotspot
     * start/stop since those don't reliably produce connectivity callbacks.
     * Emits Disconnected only when no candidate exists; never Connected("0.0.0.0").
     *
     * Returns this scan's state so a caller can act on the exact result it
     * logged — reading state.value afterwards could observe a concurrent
     * callback's newer scan.
     */
    fun refresh(forceLog: Boolean = false): NetworkState {
        val candidates = IpSelector.scan()
        val best = IpSelector.select(candidates, ReachableIp.last(context))
        UrlSelectionLogger.log(best, candidates, forceLog)
        val newState = if (best != null) {
            Log.i(TAG, "Selected IP ${best.ip} on ${best.iface} (priority=${best.priority})")
            NetworkState.Connected(best.ip)
        } else {
            Log.i(TAG, "No candidate IP — disconnected")
            NetworkState.Disconnected
        }
        _state.value = newState
        return newState
    }
}
