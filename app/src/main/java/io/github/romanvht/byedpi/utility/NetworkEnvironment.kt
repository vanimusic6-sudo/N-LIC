package io.github.romanvht.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address

/** No carrier name, SSID, addresses or network identifiers are stored. */
enum class AccessType { WIFI, CELLULAR, ETHERNET, OTHER, UNKNOWN }

internal data class AccessSnapshot(
    val type: AccessType,
    val physicalMtu: Int = 0,
    val hasIpv4: Boolean = false,
    val hasIpv6: Boolean = false,
    val hasNat64: Boolean = false,
) {
    val isCellular: Boolean get() = type == AccessType.CELLULAR
}

internal data class AccessCandidate(
    val type: AccessType,
    val validated: Boolean,
    val mtu: Int,
    val ipv4: Boolean,
    val ipv6: Boolean,
    val nat64: Boolean = false,
)

/**
 * Conservative carrier policy. It does not change a working Wi-Fi TUN path.
 * On cellular only, avoid passing oversize virtual packets into an unknown
 * radio/NAT64 path. This is an experimental workaround, not a DPI bypass.
 */
internal object AccessPolicy {
    private const val LEGACY_TUN_MTU = 8500
    const val MOBILE_TUN_MTU = 1400

    /**
     * On Android both Wi-Fi and mobile can remain VALIDATED while only one is
     * in use. Prefer the *active* non-VPN Network, never whichever radio sorts
     * first. If both remain eligible but Android exposes only a VPN as active,
     * report UNKNOWN instead of claiming Wi-Fi is the carrier.
     */
    fun select(
        candidates: List<AccessCandidate>,
        activeCandidate: AccessCandidate? = null,
        vpnUnderlyingType: AccessType? = null,
    ): AccessSnapshot {
        fun snap(c: AccessCandidate) =
            AccessSnapshot(c.type, c.mtu, c.ipv4, c.ipv6, c.nat64)
        if (activeCandidate != null && activeCandidate.type != AccessType.UNKNOWN) {
            return snap(activeCandidate)
        }
        // A VPN default Network can expose the underlying physical transport
        // in NetworkCapabilities. Use that only when it is unambiguous AND a
        // corresponding real non-VPN Network was returned by ConnectivityManager.
        // Never invent a physical network based on the VPN flags alone.
        if (vpnUnderlyingType != null && vpnUnderlyingType != AccessType.UNKNOWN) {
            candidates.firstOrNull { it.type == vpnUnderlyingType }?.let { return snap(it) }
        }
        val validated = candidates.filter { it.type != AccessType.UNKNOWN && it.validated }
        if (validated.map { it.type }.distinct().size == 1) {
            return snap(validated.first())
        }
        if (validated.isNotEmpty()) return AccessSnapshot(AccessType.UNKNOWN)
        val connected = candidates.filter { it.type != AccessType.UNKNOWN }
        return if (connected.size == 1) snap(connected.first())
            else AccessSnapshot(AccessType.UNKNOWN)
    }

    /**
     * 1280 is the mandatory IPv6 minimum. Reserve some room below path MTU
     * where possible; cap mobile virtual frames at 1400 bytes.
     */
    fun tunMtu(snapshot: AccessSnapshot, mobileTuningEnabled: Boolean = true): Int =
        if (snapshot.isCellular && mobileTuningEnabled) {
            val pathMtu = snapshot.physicalMtu
            if (pathMtu >= 1280) (pathMtu - 80).coerceIn(1280, MOBILE_TUN_MTU)
            else MOBILE_TUN_MTU
        } else LEGACY_TUN_MTU

    /** IPv6-only carrier paths must not silently bypass the VPN's TLS proxy. */
    fun shouldCaptureIpv6(
        userEnabled: Boolean,
        underlying: AccessSnapshot,
        mobileAutoEnabled: Boolean,
    ): Boolean = userEnabled || (underlying.isCellular && underlying.hasIpv6 && mobileAutoEnabled)

    fun preferCarrierDnsOnNat64(
        snapshot: AccessSnapshot,
        enabled: Boolean,
        configuredDns: String,
    ): Boolean = enabled && snapshot.isCellular &&
        (snapshot.hasNat64 || snapshot.hasIpv6 && !snapshot.hasIpv4) &&
        configuredDns == "1.1.1.1"

    fun resultsFileName(type: AccessType) =
        "proxy_test_results_${type.name.lowercase()}.json"
}

internal object NetworkEnvironment {
    fun capture(context: Context): AccessSnapshot {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return AccessSnapshot(AccessType.UNKNOWN)
        return try {
            val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.activeNetwork else null
            val activeCaps = activeNetwork?.let(manager::getNetworkCapabilities)
            val vpnHint = if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                val wifi = activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val mobile = activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                when {
                    wifi && !mobile -> AccessType.WIFI
                    mobile && !wifi -> AccessType.CELLULAR
                    else -> null
                }
            } else null
            var activeCandidate: AccessCandidate? = null
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
                    // getMtu is API 29+: invoking on Android 5-9 crashes at runtime.
                    mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        links?.mtu ?: 0
                    } else 0,
                    ipv4 = links?.linkAddresses?.any { it.address is Inet4Address } == true,
                    ipv6 = links?.linkAddresses?.any { it.address is Inet6Address } == true,
                    nat64 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        links?.nat64Prefix != null,
                ).also { if (network == activeNetwork) activeCandidate = it }
            }
            AccessPolicy.select(candidates, activeCandidate, vpnHint)
        } catch (_: SecurityException) {
            AccessSnapshot(AccessType.UNKNOWN)
        }
    }
}
