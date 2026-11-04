package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException

/**
 * Android platform DNS bridge for pinned sing-box libbox.
 *
 * Its local DNS transport MUST NOT be null: Go has no reliable Android
 * resolv.conf resolver, so a "local" bootstrap DNS config could fail
 * for VLESS and WireGuard hostnames even with a functioning physical link.
 *
 * Only A/AAAA lookups for the VPN server bootstrap use this bridge. Normal
 * app DNS remains forced through encrypted DNS-over-HTTPS in NativeTunConfig.
 * No hostnames, DNS replies, keys or server addresses are logged or stored.
 */
internal class CoreLocalDnsResolver(context: Context) {
    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Never allow the VPN's TUN to become the resolver's underlying network. */
    private fun physicalNetwork(): Network? {
        val candidates = manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            manager.activeNetwork else null
        return candidates.firstOrNull { it == active }
            ?: candidates.firstOrNull {
                manager.getNetworkCapabilities(it)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            ?: candidates.firstOrNull()
    }

    private fun lookup(exchange: Any, network: String, domain: String) {
        val upstream = physicalNetwork()
            ?: throw IllegalStateException("No physical network available for bootstrap DNS")
        val addresses = upstream.getAllByName(domain).filter {
            when (network) {
                "ip4" -> it is Inet4Address
                "ip6" -> it is Inet6Address
                else -> it is Inet4Address || it is Inet6Address
            }
        }.mapNotNull { it.hostAddress?.substringBefore('%') }
        if (addresses.isEmpty()) throw UnknownHostException("No answers for requested DNS family")
        // Gomobile ExchangeContext.Success accepts newline-separated numeric
        // addresses; never pass a scoped IPv6 zone to Go netip parser.
        exchange.javaClass.getMethod("success", String::class.java)
            .invoke(exchange, addresses.joinToString("\n"))
    }

    private val localTransport by lazy {
        val type = Class.forName("io.nekohasekai.libbox.LocalDNSTransport")
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                // Android's physical Network.getAllByName is reliable on
                // all supported OS versions. Raw DNS is not needed for
                // bootstrap: the VPN's public DNS stays on encrypted DoH.
                "raw" -> false
                "lookup" -> {
                    val exchange = requireNotNull(args?.getOrNull(0))
                    val family = args?.getOrNull(1) as? String ?: "ip"
                    val domain = args?.getOrNull(2) as? String
                        ?: throw IllegalArgumentException("Missing lookup name")
                    lookup(exchange, family, domain)
                    null
                }
                "exchange" -> throw UnsupportedOperationException(
                    "Raw platform DNS is disabled; bootstrap accepts A/AAAA only"
                )
                "toString" -> "N-LIC platform DNS"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }

    fun transport(): Any = localTransport
}
