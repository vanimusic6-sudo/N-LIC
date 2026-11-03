package io.github.romanvht.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address

/** No carrier name, SSID, addresses or network identifiers are stored. */
internal enum class AccessType { WIFI, CELLULAR, ETHERNET, OTHER, UNKNOWN }

internal data class AccessSnapshot(
    val type: AccessType,
    val physicalMtu: Int = 0,
    val hasIpv4: Boolean = false,
    val hasIpv6: Boolean = false,
) {
    val isCellular: Boolean get() = type == AccessType.CELLULAR
}

internal data class AccessCandidate(
    val type: AccessType,
    val validated: Boolean,
    val mtu: Int,
    val ipv4: Boolean,
    val ipv6: Boolean,
)

/**
 * Conservative carrier policy. It does not change a working Wi-Fi TUN path.
 * On cellular only, avoid passing oversize virtual packets into an unknown
 * radio/NAT64 path. This is an experimental workaround, not a DPI bypass.
 */
internal object AccessPolicy {
    private const val LEGACY_TUN_MTU = 8500
    const val MOBILE_TUN_MTU = 1400

    fun select(candidates: List<AccessCandidate>): AccessSnapshot {
        val candidate = candidates
            .filter { it.type != AccessType.UNKNOWN }
            .sortedWith(
                compareByDescending<AccessCandidate> { it.validated }
                    .thenBy {
                        when (it.type) {
                            AccessType.WIFI -> 0
                            AccessType.ETHERNET -> 1
                            AccessType.CELLULAR -> 2
                            AccessType.OTHER -> 3
                            AccessType.UNKNOWN -> 4
                        }
                    }
            ).firstOrNull()
        return candidate?.let { AccessSnapshot(it.type, it.mtu, it.ipv4, it.ipv6) }
            ?: AccessSnapshot(AccessType.UNKNOWN)
    }

    /**
     * 1280 is the mandatory IPv6 minimum. Reserve some room below path MTU
     * where possible; cap mobile virtual frames at 1400 bytes.
     */
    fun tunMtu(snapshot: AccessSnapshot): Int =
        if (snapshot.isCellular) {
            val pathMtu = snapshot.physicalMtu
            if (pathMtu >= 1280) (pathMtu - 80).coerceIn(1280, MOBILE_TUN_MTU)
            else MOBILE_TUN_MTU
        } else LEGACY_TUN_MTU

    fun resultsFileName(type: AccessType) =
        "proxy_test_results_${type.name.lowercase()}.json"
}

internal object NetworkEnvironment {
    fun capture(context: Context): AccessSnapshot {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return AccessSnapshot(AccessType.UNKNOWN)
        return try {
            val candidates = manager.allNetworks.mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AccessType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AccessType.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> AccessType.ETHERNET
                    else -> AccessType.OTHER
                }
                val links = manager.getLinkProperties(network)
                AccessCandidate(
                    type = type,
                    validated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    mtu = links?.mtu ?: 0,
                    ipv4 = links?.linkAddresses?.any { it.address is Inet4Address } == true,
                    ipv6 = links?.linkAddresses?.any { it.address is Inet6Address } == true,
                )
            }
            AccessPolicy.select(candidates)
        } catch (_: SecurityException) {
            AccessSnapshot(AccessType.UNKNOWN)
        }
    }
}
