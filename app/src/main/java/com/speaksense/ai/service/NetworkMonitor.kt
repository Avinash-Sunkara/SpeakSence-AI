package com.speaksense.ai.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks internet availability so engines can report "network lost" instead of failing silently. */
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(computeOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
        }

        override fun onLost(network: Network) {
            _online.value = computeOnline()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun isOnline(): Boolean = _online.value

    fun start() {
        if (registered) return
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
            // Monitoring is best-effort.
        }
        _online.value = computeOnline()
    }

    fun stop() {
        if (!registered) return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // ignore
        }
        registered = false
    }

    private fun computeOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
