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

/** A Relay screen found on the home network, with the short values it announced next to its name. */
data class FoundScreen(val name: String, val host: String, val port: Int, val attributes: Map<String, String>)

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
    fun advertise(ctx: Context, screenName: String, port: Int): () -> Unit =
        advertiseAs(ctx, TYPE, PREFIX + screenName, port, emptyMap(), "Pairing", "Waiting for another screen to find this one")

    /** Looks for screens waiting to join; see [discoverAs]. */
    fun discover(ctx: Context, onChange: (List<WaitingScreen>) -> Unit): () -> Unit =
        discoverAs(ctx, TYPE, PREFIX, "Pairing") { list -> onChange(list.map { WaitingScreen(it.name, it.host, it.port) }) }

    /**
     * Announces a service of [type] named [name] on [port], with short [attributes] next to it. Returns the
     * function that stops announcing it. [tag] and [announced] are for the log.
     */
    fun advertiseAs(
        ctx: Context, type: String, name: String, port: Int, attributes: Map<String, String>, tag: String, announced: String,
    ): () -> Unit {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return {}
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            attributes.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i(tag, announced)
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = AppLog.w(tag, "Could not announce this screen (error $errorCode)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLog.w(tag, "Could not announce this screen", e)
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
     * Looks for services of [type] whose names start with [prefix] and calls [onChange] (on the main thread)
     * with the current list each time it changes. Returns the function that stops looking.
     */
    @Suppress("DEPRECATION")
    fun discoverAs(ctx: Context, type: String, prefix: String, tag: String, onChange: (List<FoundScreen>) -> Unit): () -> Unit {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return {}
        val lock = Any()
        val found = LinkedHashMap<String, FoundScreen>()
        val serviceKind = type.trimEnd('.').substringBefore('.')
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
                        val attrs = try {
                            info.attributes.orEmpty().mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        synchronized(lock) {
                            resolving = false
                            if (ip != null && info.serviceName.startsWith(prefix)) {
                                found[info.serviceName] = FoundScreen(info.serviceName.removePrefix(prefix), ip, info.port, attrs)
                            }
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
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = AppLog.w(tag, "Could not look for screens (error $errorCode)")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains(serviceKind)) return
                synchronized(lock) { queue.addLast(info) }
                next()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { found.remove(info.serviceName) }
                publish()
            }
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AppLog.w(tag, "Could not look for screens", e)
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
