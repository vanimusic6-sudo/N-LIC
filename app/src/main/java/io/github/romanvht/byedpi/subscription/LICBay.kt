package io.github.romanvht.byedpi.subscription

/** LIC-Bay is a design feature gate, not a working VLESS tunnel. */
internal data class LICBayPlan(
    val canWrapTcp: Boolean,
    val note: String,
    val outboundDetourTag: String?,
)

internal object LICBay {
    fun plan(node: SubscriptionNode): LICBayPlan {
        if (node.protocol in setOf("HYSTERIA2", "HY2", "TUIC")) {
            return LICBayPlan(false,
                "UDP-first endpoint: ByeDPI TCP/SOCKS wrapping is not generally applicable.",
                null)
        }
        if (node.protocol !in setOf("VLESS", "VMESS", "TROJAN", "SHADOWSOCKS")) {
            return LICBayPlan(false, "Unsupported protocol", null)
        }
        return LICBayPlan(true,
            "Architectural candidate only: route the VPN CORE outbound dialer via local ByeDPI SOCKS5 " +
                "using sing-box outbound.detour. TLS fragmentation may hide the upstream " +
                "handshake, not encrypt a proxy or guarantee bypass. Requires integration tests.",
            "lic-bay-local-socks")
    }
}
