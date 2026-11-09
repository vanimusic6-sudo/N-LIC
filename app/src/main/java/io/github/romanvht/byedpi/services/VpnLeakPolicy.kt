package io.github.romanvht.byedpi.services

import io.github.romanvht.byedpi.subscription.NativeTunConfig

/**
 * Pure routing policy shared by Android VpnService and unit tests.
 *
 * Encrypted subscription modes fail closed: IPv6 is captured even when the
 * legacy preference is off, and Android never inherits the carrier resolver.
 */
internal object VpnLeakPolicy {
    const val XRAY_TUNNEL_DNS_V4 = "1.1.1.1"

    fun captureIpv6(
        coreActive: Boolean,
        xrayActive: Boolean,
        legacyCapture: Boolean,
    ): Boolean = coreActive || xrayActive || legacyCapture

    fun dnsServers(
        coreActive: Boolean,
        xrayActive: Boolean,
        configuredDns: String,
    ): List<String> = when {
        coreActive -> listOf(NativeTunConfig.IPV4_DNS)
        xrayActive -> listOf(XRAY_TUNNEL_DNS_V4)
        configuredDns.isNotBlank() -> listOf(configuredDns)
        else -> emptyList()
    }
}
