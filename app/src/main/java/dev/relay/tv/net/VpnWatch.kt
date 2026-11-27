package dev.relay.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.relay.tv.data.AppLog

/**
 * Whether this app's network goes through a VPN, so the provider's servers can be tried in the right
 * order (see HostOrder). It watches the app's own default network, so a VPN app that leaves Relay out
 * (split tunnelling) correctly counts as no VPN. [vpnOn] is null when unknown; then servers keep their
 * saved order, as before. [onChange] runs (on a system thread) when the VPN goes from on to off or back.
 */
class VpnWatch(context: Context, private val onChange: (Boolean) -> Unit) {
    private val app = context.applicationContext

    @Volatile var vpnOn: Boolean? = null
        private set

    @Volatile private var lastKnown: Boolean? = null
    @Volatile private var activeNet: Network? = null

    private fun set(v: Boolean) {
        val before = lastKnown
        vpnOn = v
        lastKnown = v
        if (before != null && before != v) onChange(v)
    }

    /** Call once, at app start. Never stopped: it lives as long as the process. */
    fun start() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            val current = cm.activeNetwork
            activeNet = current
            cm.getNetworkCapabilities(current)?.let { caps ->
                val v = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                vpnOn = v
                lastKnown = v
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    activeNet = network
                    set(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                }

                override fun onLost(network: Network) {
                    if (network == activeNet) {
                        val fallback = runCatching { cm.activeNetwork }.getOrNull()?.takeIf { it != network }
                        val caps = fallback?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
                        if (fallback != null && caps != null) {
                            activeNet = fallback
                            set(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        } else {
                            activeNet = null
                            vpnOn = null
                        }
                    }
                }
            })
        }.onFailure { AppLog.w("Network", "Can't watch for a VPN; servers keep their saved order", it) }
        AppLog.i("Network", "VPN: ${describe(vpnOn)}")
    }

    companion object {
        fun describe(v: Boolean?): String = when (v) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }
    }
}
