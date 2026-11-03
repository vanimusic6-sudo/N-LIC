package io.github.romanvht.byedpi.subscription

/**
 * Transport-aware routing for the PINNED hufrea/byedpi native code.
 *
 * This pinned revision implements SOCKS5 CONNECT and UDP ASSOCIATE.
 * QUIC/WireGuard datagrams are sent to ByeDPI's UDP relay; they are not
 * magically converted into TCP or guaranteed to bypass UDP filtering.
 */
internal enum class BayTransport { TCP_CONNECT, UDP_ASSOCIATE }

internal data class LICBayPlan(
    val supported: Boolean,
    val transport: BayTransport?,
    val note: String,
    val outboundDetourTag: String?,
) {
    // Compatibility with older call sites.
    val canWrapTcp: Boolean get() = supported && transport == BayTransport.TCP_CONNECT
}

internal object LICBay {
    fun plan(node: SubscriptionNode): LICBayPlan {
        val proto = node.protocol.uppercase()
        val transport = when (proto) {
            "VLESS", "VMESS", "TROJAN", "SHADOWSOCKS", "ANYTLS" ->
                BayTransport.TCP_CONNECT
            "HYSTERIA2", "HY2", "TUIC", "WIREGUARD" ->
                BayTransport.UDP_ASSOCIATE
            else -> return LICBayPlan(false, null,
                "Unsupported VPN protocol. No silent direct fallback.", null)
        }
        return LICBayPlan(true, transport,
            if (transport == BayTransport.TCP_CONNECT)
                "Encrypted TCP outbound via ByeDPI SOCKS5 CONNECT."
            else
                "UDP packets via ByeDPI SOCKS5 UDP ASSOCIATE. UDP remains " +
                    "UDP; filtering may still block QUIC/WireGuard and fake " +
                    "packet features require explicit validation.",
            SingBoxConfigCompiler.BAY_TAG)
    }
}
