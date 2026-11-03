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
                add("172.18.0.1/30")
                add("fdfe:dcba:9876::1/126")
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
            addProperty("detour", SingBoxConfigCompiler.PROXY_TAG)
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", "cloudflare-dns.com")
                addProperty("insecure", false)
            })
        })
        dns.addProperty("final", "lic-private-doh")
        return root.toString()
    }
}
