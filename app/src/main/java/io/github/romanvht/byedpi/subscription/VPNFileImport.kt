package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Deliberately narrow, *data-only* import. A file can never replace
 * inbounds/routes, enable insecure TLS, change local proxy listeners or run
 * provider-defined scripts. It only contributes named remote endpoints to
 * the encrypted SubscriptionVault.
 *
 * WireGuard uses the sing-box 1.14 **endpoint** schema (outbound removed in
 * 1.13), not the old wireguard outbound. Provider private keys stay encrypted.
 */
internal object VPNFileImport {
    const val MAX_BYTES = 512 * 1024
    private val ALLOWED_EXT = setOf("conf", "wg", "txt", "json", "base64", "b64")
    private val safeLabel = Regex("[^\\p{L}\\p{N} _.,()\\-+]")

    fun parse(filename: String, content: String): List<SubscriptionNode> {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "File too large" }
        val extension = filename.substringAfterLast('.', "").lowercase()
        require(extension in ALLOWED_EXT) { "Unsupported file format" }
        val name = filename.substringBeforeLast('.', filename)
            .replace(safeLabel, "").trim().take(65).ifEmpty { "VPN profile" }
        val cleaned = content.removePrefix("\uFEFF").trim()
        require(cleaned.isNotEmpty()) { "Empty VPN file" }
        return when {
            cleaned.startsWith("[Interface]", true) ||
                cleaned.lines().any { it.trim().equals("[Interface]", true) } ->
                listOfNotNull(importWireGuard(name, cleaned))
            cleaned.startsWith("{") || cleaned.startsWith("[") ->
                parseJson(name, cleaned)
            else -> SubscriptionImport.parse(cleaned, name)
        }.take(100).also { require(it.isNotEmpty()) { "No supported secure profiles" } }
    }

    private fun parseJson(name: String, text: String): List<SubscriptionNode> {
        val tree = JsonParser.parseString(text)
        if (tree.isJsonArray) return tree.asJsonArray.mapNotNull { item ->
            runCatching { SubscriptionImport.parseNode(item.asString, name) }.getOrNull()
        }
        val obj = tree.asJsonObject
        val links = when {
            obj.has("configs") -> obj.getAsJsonArray("configs")
            obj.has("links") -> obj.getAsJsonArray("links")
            obj.has("servers") && obj.get("servers").isJsonArray ->
                obj.getAsJsonArray("servers")
            else -> null
        }
        if (links != null) return links.take(100).mapNotNull { item ->
            runCatching { SubscriptionImport.parseNode(item.asString, name) }.getOrNull()
        }
        // A full sing-box JSON config is intentionally NOT executed as code.
        // Import only recognized secure remote outbounds, never its DNS,
        // routing, 'direct' nodes, inbounds, experimental or exec fields.
        val outbounds = obj.getAsJsonArray("outbounds") ?: return emptyList()
        return outbounds.take(100).mapNotNull { element ->
            runCatching {
                val outbound = element.asJsonObject
                if (outbound.has("protocol") && !outbound.has("type")) {
                    return@runCatching importXrayXhttp(name, outbound)
                }
                val type = outbound.get("type")?.asString ?: return@runCatching null
                if (type !in setOf("vless", "vmess", "trojan",
                        "shadowsocks", "hysteria2", "tuic", "anytls")) return@runCatching null
                val server = outbound.get("server")?.asString ?: return@runCatching null
                val port = outbound.get("server_port")?.asInt ?: return@runCatching null
                if (!safeEndpoint(server, port) || recursiveInsecure(outbound)) return@runCatching null
                if (type in setOf("vless", "trojan", "anytls", "hysteria2", "tuic") &&
                    outbound.getAsJsonObject("tls")?.get("enabled")?.asBoolean != true)
                    return@runCatching null
                // Strip unrelated/untrusted outbound keys and preserve only
                // audited data keys that are supported by pinned sing-box.
                val whitelist = setOf("type", "server", "server_port", "uuid",
                    "password", "flow", "security", "alter_id", "method", "tls",
                    "transport", "obfs", "up_mbps", "down_mbps", "congestion_control",
                    "udp_relay_mode", "zero_rtt_handshake", "heartbeat",
                    "idle_session_check_interval", "idle_session_timeout")
                val filtered = JsonObject()
                for ((key, value) in outbound.entrySet()) {
                    if (key in whitelist) filtered.add(key, value.deepCopy())
                }
                val real = "nlicjson://" + URLEncoder.encode(filtered.toString(), "UTF-8")
                val label = outbound.get("tag")?.asString?.take(65)?.replace(safeLabel, "")
                    ?.ifBlank { null } ?: "$name · $type"
                makeNode(real, label, type.uppercase(), server, port, name)
            }.getOrNull()
        }
    }

    /**
     * Secure data-only import of explicit VLESS/XHTTP Xray JSON outbounds.
     * Never execute provider-defined Xray inbounds, DNS, routing, proxy
     * chains or experimental settings. Convert only a validated remote
     * endpoint into N-LIC's bounded, encrypted node representation.
     */
    private fun importXrayXhttp(name: String, src: JsonObject): SubscriptionNode? {
        if (src.get("protocol")?.asString != "vless" || recursiveInsecure(src))
            return null
        val stream = src.getAsJsonObject("streamSettings") ?: return null
        if (stream.get("network")?.asString !in setOf("xhttp", "splithttp")) return null
        val security = stream.get("security")?.asString ?: return null
        if (security !in setOf("tls", "reality")) return null
        val xhttp = stream.getAsJsonObject("xhttpSettings")
            ?: stream.getAsJsonObject("splithttpSettings") ?: return null
        // Do not silently drop a separate upstream/download destination.
        if (xhttp.has("downloadSettings")) return null
        val next = src.getAsJsonObject("settings")?.getAsJsonArray("vnext")
            ?.takeIf { it.size() == 1 }?.firstOrNull()?.asJsonObject
            ?: return null
        val users = next.getAsJsonArray("users")?.takeIf { it.size() == 1 }
            ?: return null
        val user = users[0].asJsonObject
        val host = next.get("address")?.asString ?: return null
        val port = next.get("port")?.asInt ?: return null
        if (!safeEndpoint(host, port) ||
            user.get("flow")?.asString.orEmpty().isNotBlank()) return null
        val id = user.get("id")?.asString ?: return null
        val normalized = JsonObject().apply {
            addProperty("type", "vless")
            addProperty("server", host)
            addProperty("server_port", port)
            addProperty("uuid", id)
            add("transport", JsonObject().apply {
                addProperty("type", "xhttp")
                addProperty("path", xhttp.get("path")?.asString ?: "/")
                xhttp.get("host")?.asString?.let { addProperty("host", it) }
                addProperty("mode", xhttp.get("mode")?.asString ?: "auto")
                xhttp.getAsJsonObject("extra")?.let {
                    add("extra", it.deepCopy())
                }
            })
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                if (security == "reality") {
                    val reality = stream.getAsJsonObject("realitySettings")
                        ?: return null
                    addProperty("server_name", reality.get("serverName")?.asString
                        ?: return null)
                    reality.get("fingerprint")?.asString?.let { fp ->
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", fp)
                        })
                    }
                    add("reality", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("public_key", reality.get("password")?.asString
                            ?: reality.get("publicKey")?.asString ?: return null)
                        addProperty("short_id", reality.get("shortId")?.asString ?: "")
                        addProperty("spider_x", reality.get("spiderX")?.asString ?: "/")
                    })
                } else {
                    val tls = stream.getAsJsonObject("tlsSettings") ?: return null
                    addProperty("server_name", tls.get("serverName")?.asString
                        ?: return null)
                    tls.get("fingerprint")?.asString?.let { fp ->
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", fp)
                        })
                    }
                    tls.getAsJsonArray("alpn")?.let { add("alpn", it.deepCopy()) }
                }
            })
        }
        val raw = "nlicjson://" + URLEncoder.encode(normalized.toString(), "UTF-8")
        val label = src.get("tag")?.asString?.take(65)?.replace(safeLabel, "")
            ?.ifBlank { null } ?: "$name · VLESS/XHTTP"
        return makeNode(raw, label, "VLESS", host, port, name)
    }

    private fun recursiveInsecure(value: com.google.gson.JsonElement): Boolean {
        if (value.isJsonArray) return value.asJsonArray.any(::recursiveInsecure)
        if (!value.isJsonObject) return false
        return value.asJsonObject.entrySet().any { (key, v) ->
            (key in setOf("insecure", "allowInsecure", "skip-cert-verify",
                "skip_cert_verify", "allow_insecure") && runCatching {
                    v.asBoolean
                }.getOrDefault(false)) || recursiveInsecure(v)
        }
    }

    fun parseSecureJson(node: SubscriptionNode): JsonObject? {
        return try {
        if (node.protocol !in setOf("VLESS", "VMESS", "TROJAN",
                "SHADOWSOCKS", "HYSTERIA2", "TUIC", "ANYTLS")) return null
        val raw = URLDecoder.decode(node.originalUri.substringAfter("nlicjson://"), "UTF-8")
        if (raw.length > 16_384) return null
        val obj = JsonParser.parseString(raw).asJsonObject
        if (obj.get("type")?.asString?.uppercase() != node.protocol ||
            obj.get("server")?.asString != node.host ||
            obj.get("server_port")?.asInt != node.port ||
            recursiveInsecure(obj)) return null
        obj
        } catch (_: Exception) { null }
    }

    private fun importWireGuard(name: String, data: String): SubscriptionNode? {
        val profile = wireguard(data) ?: return null
        val stored = "wgconf://" + URLEncoder.encode(data, "UTF-8")
        return makeNode(stored, name, "WIREGUARD", profile.host, profile.port, name)
    }

    internal data class WireGuardProfile(
        val host: String, val port: Int, val endpoint: JsonObject,
    )

    /**
     * Standard wg-quick data with exactly one peer. Rejects shell commands,
     * local hooks, duplicate keys, external include files and non-full-tunnel
     * AllowedIPs rather than silently routing plaintext outside the tunnel.
     */
    fun wireguard(text: String): WireGuardProfile? {
        return try {
        if (text.length !in 60..65_536) return null
        val iface = linkedMapOf<String, String>()
        val peer = linkedMapOf<String, String>()
        var section: MutableMap<String, String>? = null
        var peerCount = 0
        for (raw in text.lines()) {
            val line = raw.substringBefore("#").trim()
            if (line.isBlank() || line.startsWith(";")) continue
            when {
                line.equals("[Interface]", true) -> { section = iface }
                line.equals("[Peer]", true) -> { peerCount++; section = peer }
                line.startsWith("[") -> return null
                else -> {
                    val key = line.substringBefore("=", "").trim().lowercase()
                    val value = line.substringAfter("=", "").trim()
                    val map = section ?: return null
                    if (key !in setOf("privatekey", "address", "dns", "mtu",
                            "publickey", "presharedkey", "endpoint",
                            "allowedips", "persistentkeepalive") ||
                        key in map || value.length > 2048) return null
                    map[key] = value
                }
            }
        }
        if (peerCount != 1) return null
        val privateKey = iface["privatekey"] ?: return null
        val publicKey = peer["publickey"] ?: return null
        if (!validKey(privateKey) || !validKey(publicKey)) return null
        val psk = peer["presharedkey"]
        if (psk != null && !validKey(psk)) return null
        val addr = iface["address"]?.split(',')?.map(String::trim)
            ?.filter(String::isNotEmpty) ?: return null
        if (addr.isEmpty() || addr.size > 4 || !addr.all(::validCidr)) return null
        val allowed = peer["allowedips"]?.split(',')?.map(String::trim)
            ?.filter(String::isNotEmpty) ?: return null
        if ("0.0.0.0/0" !in allowed || !allowed.all(::validCidr)) return null
        val endpointAddress = peer["endpoint"] ?: return null
        val (host, port) = splitHostPort(endpointAddress) ?: return null
        val mtu = iface["mtu"]?.toIntOrNull() ?: 1408
        if (mtu !in 1280..1500) return null
        val keepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 25
        if (keepalive !in 0..120) return null
        val cfg = JsonObject().apply {
            addProperty("type", "wireguard")
            addProperty("tag", SingBoxConfigCompiler.PROXY_TAG)
            addProperty("system", false)
            addProperty("mtu", mtu)
            add("address", JsonArray().apply { addr.forEach { add(it) } })
            addProperty("private_key", privateKey)
            add("peers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", port)
                    addProperty("public_key", publicKey)
                    if (psk != null) addProperty("pre_shared_key", psk)
                    add("allowed_ips", JsonArray().apply { allowed.forEach { add(it) } })
                    addProperty("persistent_keepalive_interval", keepalive)
                })
            })
        }
        WireGuardProfile(host, port, cfg)
        } catch (_: Exception) { null }
    }

    fun readWireGuard(node: SubscriptionNode): WireGuardProfile? {
        if (node.protocol != "WIREGUARD" ||
            !node.originalUri.startsWith("wgconf://")) return null
        return runCatching {
            wireguard(URLDecoder.decode(node.originalUri.substringAfter("wgconf://"),
                "UTF-8"))
        }.getOrNull()?.takeIf { it.host == node.host && it.port == node.port }
    }

    private fun splitHostPort(raw: String): Pair<String, Int>? {
        return try {
        val host: String
        val port: Int
        if (raw.startsWith("[")) {
            host = raw.substringAfter("[").substringBefore("]")
            port = raw.substringAfter("]:").toInt()
        } else {
            val parsed = URI("wg://$raw")
            host = parsed.host ?: return null
            port = parsed.port
        }
        if (!safeEndpoint(host, port)) null else host to port
        } catch (_: Exception) { null }
    }

    // Standard WireGuard keys are exactly 32 bytes (43 base64 sextets +
    // one padding character). Validate canonical padding and alphabet
    // without java.util.Base64, which is missing on Android API 24/25.
    private fun validKey(key: String): Boolean {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        if (key.length != 44 || key.last() != '=' ||
            !key.take(43).all { it in alphabet }) return false
        val finalSextet = alphabet.indexOf(key[42])
        return finalSextet >= 0 && (finalSextet and 0x3) == 0
    }

    private fun validCidr(raw: String): Boolean {
        val (address, mask) = raw.split('/').takeIf { it.size == 2 }
            ?.let { it[0] to it[1].toIntOrNull() } ?: return false
        if (mask == null) return false
        return when {
            address.contains(':') -> mask in 0..128 &&
                address.all { it in "0123456789abcdefABCDEF:." }
            else -> mask in 0..32 && address.split('.').size == 4 &&
                address.split('.').all {
                    val byte = it.toIntOrNull()
                    byte != null && byte in 0..255 && it.isNotEmpty()
                }
        }
    }

    private fun safeEndpoint(host: String, port: Int) =
        host.isNotBlank() && host.length <= 253 && port in 1..65535 &&
            host.none { it.isWhitespace() || it in "/\\@" || it.code < 33 }

    private fun makeNode(raw: String, label: String, proto: String,
                         host: String, port: Int, group: String): SubscriptionNode {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8)).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return SubscriptionNode(id, label.take(70), proto, host, port, raw, group)
    }
}
