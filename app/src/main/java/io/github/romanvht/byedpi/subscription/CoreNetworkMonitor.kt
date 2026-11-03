package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.lang.reflect.Proxy
import java.net.NetworkInterface

/** Event-driven physical-interface bridge for the pinned libbox API. No polling. */
internal class CoreNetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private var listener: Any? = null
    private var registered = false
    private var lastUpdate: String? = null
    // A default-network callback switches to the VPN interface once Android
    // installs our TUN. Observe PHYSICAL transports instead so Wi-Fi/cellular
    // handovers continue reaching libbox even while the VPN is connected.
    private val physicalRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = update()
    }

    fun start(value: Any) {
        synchronized(lock) {
            listener = value
            lastUpdate = null
            if (!registered) {
                manager.registerNetworkCallback(physicalRequest, callback)
                registered = true
            }
        }
        update()
    }

    private fun physicalNetworks(exclude: Network? = null): List<Network> =
        manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network)
            network != exclude && caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

    private fun update(exclude: Network? = null) {
        try {
            val candidates = physicalNetworks(exclude)
            val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.activeNetwork else null
            val network = candidates.firstOrNull { it == active } ?: candidates.firstOrNull {
                manager.getNetworkCapabilities(it)?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            } ?: candidates.firstOrNull()
            val links = network?.let(manager::getLinkProperties)
            val name = links?.interfaceName.orEmpty()
            val index = name.takeIf(String::isNotEmpty)?.let(NetworkInterface::getByName)?.index ?: -1
            val caps = network?.let(manager::getNetworkCapabilities)
            val expensive = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val key = "$name/$index/$expensive"
            val target = synchronized(lock) {
                if (lastUpdate == key) null else listener?.also { lastUpdate = key }
            } ?: return
            target.javaClass.methods.first { it.name == "updateDefaultInterface" }
                .invoke(target, name, index, expensive, false)
        } catch (_: Exception) {
            // Do not invent interface data when Android hides the physical path.
        }
    }

    fun interfaces(): Any {
        val entries = runCatching {
            physicalNetworks().mapNotNull { network ->
                val links = manager.getLinkProperties(network) ?: return@mapNotNull null
                val name = links.interfaceName ?: return@mapNotNull null
                val iface = NetworkInterface.getByName(name) ?: return@mapNotNull null
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val entry = Class.forName(PREFIX + "NetworkInterface").getConstructor().newInstance()
                fun set(name: String, type: Class<*>, value: Any) {
                    entry.javaClass.getMethod(name, type).invoke(entry, value)
                }
                set("setName", String::class.java, name)
                set("setIndex", Int::class.javaPrimitiveType!!, iface.index)
                set("setMTU", Int::class.javaPrimitiveType!!, iface.mtu.coerceAtLeast(1280))
                set("setFlags", Int::class.javaPrimitiveType!!, 1 or 64 or 4096)
                set("setMetered", Boolean::class.javaPrimitiveType!!,
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                val strings = Class.forName(PREFIX + "StringIterator")
                set("setAddresses", strings, iterator("StringIterator",
                    links.linkAddresses.map { it.toString() }))
                set("setDNSServer", strings, iterator("StringIterator",
                    links.dnsServers.mapNotNull { it.hostAddress }))
                entry
            }
        }.getOrDefault(emptyList())
        return iterator("NetworkInterfaceIterator", entries)
    }

    private fun iterator(type: String, values: List<Any>): Any {
        val clazz = Class.forName(PREFIX + type)
        val iter = values.iterator()
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { proxy, method, args ->
            when (method.name) {
                "hasNext" -> iter.hasNext()
                "next" -> if (iter.hasNext()) iter.next() else null
                "toString" -> "N-LIC interface iterator"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            listener = null
            lastUpdate = null
            if (registered) {
                registered = false
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
    }

    companion object { private const val PREFIX = "io.nekohasekai.libbox." }
}
