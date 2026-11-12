package dev.relay.tv.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import dev.relay.tv.data.AppLog
import java.net.Inet4Address

/** A screen that is waiting to join sync, found on the home network. */
data class WaitingScreen(val name: String, val host: String, val port: Int)

/**
 * Finding each other on the home network without typing an address, with Android's own network
 * service discovery (`_relay-pair._tcp`). It needs no permission beyond the internet access Relay has.
 * Some guest or mesh networks block it; the pairing link from a phone is the way round that.
 */
object PairDiscovery {
    private const val TYPE = "_relay-pair._tcp."
    private const val PREFIX = "Relay pairing "
    private val main = Handler(Looper.getMainLooper())

    /** Makes this screen findable while the join window is open. Returns the function that stops it. */
    fun advertise(ctx: Context, screenName: String, port: Int): () -> Unit {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return {}
        val info = NsdServiceInfo().apply {
            serviceName = PREFIX + screenName
            serviceType = TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("Pairing", "Waiting for another screen to find this one")
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = AppLog.w("Pairing", "Could not announce this screen (error $errorCode)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLog.w("Pairing", "Could not announce this screen", e)
            return {}
        }
        return {
            try {
                nsd.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Looks for screens waiting to join and calls [onChange] (on the main thread) with the current list
     * each time it changes. Returns the function that stops looking.
     */
    @Suppress("DEPRECATION")
    fun discover(ctx: Context, onChange: (List<WaitingScreen>) -> Unit): () -> Unit {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return {}
        val lock = Any()
        val found = LinkedHashMap<String, WaitingScreen>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun publish() {
            val list = synchronized(lock) { found.values.toList() }
            main.post { onChange(list) }
        }

        // Android resolves one service at a time.
        fun next() {
            val s = synchronized(lock) {
                if (resolving) return
                val n = queue.removeFirstOrNull() ?: return
                resolving = true
                n
            }
            try {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        next()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = (info.host as? Inet4Address)?.hostAddress
                        synchronized(lock) {
                            resolving = false
                            if (ip != null) found[info.serviceName] = WaitingScreen(info.serviceName.removePrefix(PREFIX), ip, info.port)
                        }
                        publish()
                        next()
                    }
                })
            } catch (e: Exception) {
                synchronized(lock) { resolving = false }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = AppLog.w("Pairing", "Could not look for screens (error $errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("_relay-pair")) return
                synchronized(lock) { queue.addLast(info) }
                next()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { found.remove(info.serviceName) }
                publish()
            }
        }
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLog.w("Pairing", "Could not look for screens", e)
            return {}
        }
        return {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
    }
}
