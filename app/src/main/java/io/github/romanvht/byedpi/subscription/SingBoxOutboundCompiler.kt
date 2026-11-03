package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * Conservative configuration compiler for future pinned sing-box/libbox.
 * This constructs an OUTBOUND ONLY; it cannot establish a VPN and it MUST
 * NOT be logged, shared, or stored outside the encrypted subscription vault.
 *
 * Unsupported transports are rejected rather than silently dropping TLS
 * settings (which could connect to the wrong server/route).
 */
internal object SingBoxOutboundCompiler {
    internal data class CompiledOutbound(
        val config: JsonObject,
        val usesLicBay: Boolean,
    )

    private val allowedVless = setOf(
        "encryption", "security", "sni", "alpn", "fp", "pbk",
        "sid", "spx", "type", "flow", "host", "path"
    )
    private val allowedTrojan = setOf(
        "security", "sni", "alpn", "fp", "type", "host", "path"
    )

    fun compile(node: SubscriptionNode, licBay: Boolean, localSocksPort: Int = 1080): CompiledOutbound {
        require(node.protocol == "VLESS" || node.protocol == "TROJAN") {
            "Only VLESS and Trojan TCP outbounds are supported by the prototype compiler"
        }
        require(node.port in 1..65535 && node.host.isNotBlank()) { "Invalid server endpoint" }
        require(!licBay || localSocksPort in 1..65535) { "Invalid local SOCKS port" }
        val uri = try { URI(node.originalUri) }
            catch (_: Exception) { throw IllegalArgumentException("Malformed node URI") }
        val protocol = node.protocol.lowercase()
        require(uri.scheme.equals(protocol, ignoreCase = true)) { "Protocol mismatch" }
        require(uri.host == node.host && uri.port == node.port) { "Server endpoint mismatch" }
        val query = parseQuery(uri.rawQuery)
        val supported = if (protocol == "vless") allowedVless else allowedTrojan
        require(query.keys.all { it in supported }) { "Unsupported transport parameter" }
        val transport = query["type"]?.lowercase().orEmpty().ifEmpty { "tcp" }
        require(transport == "tcp") { "Transport not supported by the prototype compiler" }
        require(query["host"].isNullOrBlank() && query["path"].isNullOrBlank()) {
            "Unsupported HTTP/WebSocket transport fields"
        }
        // "fp" controls uTLS fingerprint; we cannot silently ignore it.
        require(query["fp"].isNullOrBlank() || query["fp"] == "chrome") {
            "Unsupported TLS fingerprint"
        }

        val outbound = JsonObject().apply {
            addProperty("type", protocol)
            addProperty("tag", "nlic-selected")
            addProperty("server", node.host)
            addProperty("server_port", node.port)
        }
        val user = try { URLDecoder.decode(uri.rawUserInfo ?: "", "UTF-8") }
            catch (_: Exception) { throw IllegalArgumentException("Invalid credentials") }
        require(user.isNotBlank() && user.length <= 512 && !user.contains(':') || protocol == "trojan" && user.isNotBlank()) {
            "Missing or invalid credentials"
        }
        if (protocol == "vless") {
            try { UUID.fromString(user) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid VLESS UUID")
            }
            require(query["encryption"].isNullOrBlank() || query["encryption"] == "none") {
                "VLESS encryption field is unsupported"
            }
            outbound.addProperty("uuid", user)
            query["flow"]?.takeIf(String::isNotBlank)?.let { flow ->
                require(flow == "xtls-rprx-vision") { "Unsupported VLESS flow" }
                outbound.addProperty("flow", flow)
            }
        } else {
            outbound.addProperty("password", user)
        }

        val sec = query["security"].orEmpty().ifEmpty {
            if (protocol == "trojan") "tls" else ""
        }
        require(sec in setOf("tls", "reality")) {
            "Only TLS/REALITY encrypted TCP outbounds are allowed"
        }
        require(sec != "reality" || protocol == "vless") {
            "REALITY is only available for VLESS in the prototype"
        }
        require(!(query["flow"] != null && sec != "reality")) {
            "Vision requires an explicitly validated REALITY profile in this prototype"
        }
        val sni = query["sni"].orEmpty().ifBlank { node.host }
        require(sni.length <= 253 && sni.none { it.isWhitespace() }) { "Invalid SNI" }
        val tls = JsonObject().apply {
            addProperty("enabled", true)
            addProperty("server_name", sni)
            if (query["fp"] == "chrome") {
                add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", "chrome")
                })
            }
            query["alpn"]?.takeIf(String::isNotBlank)?.let {
                val values = it.split(',').filter(String::isNotBlank)
                require(values.isNotEmpty() && values.size <= 4 &&
                    values.all { protocol -> protocol in setOf("h2", "http/1.1") }) {
                    "Unsupported ALPN"
                }
                add("alpn", JsonArray().apply { values.forEach(::add) })
            }
        }
        if (sec == "reality") {
            val key = query["pbk"].orEmpty()
            require(key.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid REALITY public key" }
            val sid = query["sid"].orEmpty()
            require(sid.length <= 16 && sid.length % 2 == 0 &&
                sid.all { it in "0123456789abcdefABCDEF" }) { "Invalid REALITY short ID" }
            require(query["spx"].isNullOrBlank() || query["spx"] == "/") {
                "Unsupported REALITY spiderX"
            }
            tls.add("reality", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("public_key", key)
                addProperty("short_id", sid)
            })
        } else {
            require(query["pbk"].isNullOrBlank() && query["sid"].isNullOrBlank() &&
                query["spx"].isNullOrBlank()) { "Unexpected REALITY parameters" }
        }
        outbound.add("tls", tls)
        if (licBay) outbound.addProperty("detour", "lic-bay-local-socks")
        return CompiledOutbound(outbound, licBay)
    }

    fun buildOutbounds(node: SubscriptionNode, licBay: Boolean, port: Int = 1080): JsonArray {
        val compiled = compile(node, licBay, port)
        return JsonArray().apply {
            add(compiled.config)
            if (licBay) {
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "lic-bay-local-socks")
                    addProperty("server", "127.0.0.1")
                    addProperty("server_port", port)
                    addProperty("version", "5")
                })
            }
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        require(raw.length <= 4096) { "Transport query too large" }
        val map = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            val key = decode(part.substringBefore('=')).lowercase()
            val value = decode(part.substringAfter('=', ""))
            require(key.isNotBlank() && key.length <= 64 && value.length <= 1024 &&
                map.putIfAbsent(key, value) == null) { "Invalid or duplicate query parameter" }
        }
        return map
    }

    private fun decode(raw: String): String =
        try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }
        catch (_: Exception) { throw IllegalArgumentException("Invalid query encoding") }
}
