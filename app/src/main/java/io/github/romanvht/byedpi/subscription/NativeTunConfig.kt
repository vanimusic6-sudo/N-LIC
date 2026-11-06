package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Native-first Android VPN core: one sing-box TUN inbound, no HEV intermediary.
 * The pre-existing authenticated loopback SOCKS inbound remains ONLY for the
 * encrypted readiness test, never used as a second VPN data path.
 *
 * The platform bridge creates the actual Android VpnService TUN through
 * libbox.PlatformInterface.openTun(), using the same FD that sing-box reads.
 */
internal object NativeTunConfig {
    const val TAG = "lic-tun-in"
    // Keep VpnService.Builder and sing-box TUN addresses exactly aligned.
    // A /32 Android address with a /30 native address can break DNS/routing.
    const val IPV4_ADDRESS = "172.18.0.1"
    const val IPV4_PREFIX = 30
    const val IPV4_DNS = "172.18.0.2"
    const val IPV6_ADDRESS = "fdfe:dcba:9876::1"
    const val IPV6_PREFIX = 126
    fun compile(proxyConfig: String, mtu: Int): String {
        require(mtu in 1280..9000)
        val root = JsonParser.parseString(proxyConfig).asJsonObject.deepCopy()
        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray()
        require(inbounds.none { it.asJsonObject.get("type")?.asString == "tun" }) {
            "Exactly one Android VPN interface is allowed"
        }
        val nativeTun = JsonObject().apply {
            addProperty("type", "tun")
            addProperty("tag", TAG)
            addProperty("mtu", mtu)
            // Android gives the interface name itself. We own exactly one
            // VpnService, unlike desktop TUN auto-routing.
            add("address", JsonArray().apply {
                add("$IPV4_ADDRESS/$IPV4_PREFIX")
                add("$IPV6_ADDRESS/$IPV6_PREFIX")
            })
            addProperty("auto_route", true)
            addProperty("stack", "mixed")
            addProperty("dns_mode", "hijack")
        }
        val reordered = JsonArray()
        reordered.add(nativeTun)
        inbounds.forEach { reordered.add(it.deepCopy()) }
        root.add("inbounds", reordered)

        // The existing "local" DNS is *bootstrap only*: it may reveal
        // the VPN server hostname to the underlying resolver. Queries
        // originating from applications must go via a verified HTTPS
        // resolver *through the encrypted VPN outbound*. If that fails,
        // do not fall back to plaintext DNS on the physical network.
        val dns = root.getAsJsonObject("dns")
            ?: error("Native VPN requires explicit DNS configuration")
        dns.getAsJsonArray("servers").add(JsonObject().apply {
            addProperty("type", "https")
            addProperty("tag", "lic-private-doh")
            addProperty("server", "1.1.1.1")
            addProperty("server_port", 443)
            addProperty("path", "/dns-query")
            // LIC-Auto compiles to the "lic-auto" URLTest tag;
            // single-node and WireGuard compile to "lic-vpn". A static
            // detour breaks EVERY auto-select config in native libbox.
            val encryptedOutbound = root.getAsJsonObject("route")
                ?.get("final")?.asString
                ?: error("Encrypted VPN outbound missing")
            require(encryptedOutbound == "lic-auto" ||
                encryptedOutbound == SingBoxConfigCompiler.PROXY_TAG)
            addProperty("detour", encryptedOutbound)
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", "cloudflare-dns.com")
                addProperty("insecure", false)
            })
        })
        // Bounded in-memory LRU avoids repeated encrypted lookups and radio
        // wakeups. Do not enable persistent DNS cache or ECS metadata.
        dns.addProperty("cache_capacity", 2048)
        dns.addProperty("timeout", "8s")
        dns.addProperty("final", "lic-private-doh")
        return root.toString()
    }
}
