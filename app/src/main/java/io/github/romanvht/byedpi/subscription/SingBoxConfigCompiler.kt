package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * A fail-closed sing-box 1.14 compatible CONFIG compiler.
 *
 * This does not start libbox, advertise connectivity, or write plaintext
 * passwords to disk. Only feed it into an audited, pinned libbox runtime.
 *
 * The output is intentionally a loopback SOCKS listener, NOT a second TUN.
 * N-LIC's existing HEV VpnService must own the Android TUN exactly once.
 */
internal object SingBoxConfigCompiler {
    const val PROXY_TAG = "lic-vpn"
    const val BAY_TAG = "lic-bay-local-socks"
    const val LOCAL_SOCKS_TAG = "lic-private-inbound"
    const val PROXY_PORT = 11089

    enum class Bay { OFF, ON }

    sealed class Result {
        data class Compiled(val config: String, val protocol: String) : Result()
        data class Unsupported(val reason: String) : Result()
    }

    fun compile(
        node: SubscriptionNode,
        bay: Bay = Bay.OFF,
        bayPort: Int = 1080,
        localPassword: String? = null,
    ): Result {
        if (localPassword != null && (localPassword.length !in 24..128 ||
                !localPassword.all { it in "0123456789abcdef" })) {
            return Result.Unsupported("Invalid internal SOCKS credentials")
        }
        if (bayPort !in 1..65535 || bayPort == PROXY_PORT) {
            return Result.Unsupported("Invalid or conflicting LIC-Bay port")
        }
        return try {
            val outbound = decodeOutbound(node) ?: return Result.Unsupported(
                "This configuration or transport is not supported by LIC-Core yet"
            )
            val protocol = node.protocol.uppercase()
            if (bay == Bay.ON && protocol in setOf("HYSTERIA2", "HY2", "TUIC")) {
                return Result.Unsupported("LIC-Bay requires a TCP-based outbound")
            }
            if (bay == Bay.ON && !LICBay.plan(node).canWrapTcp) {
                return Result.Unsupported("LIC-Bay is not supported for this outbound")
            }
            val root = JsonObject()
            root.add("log", JsonObject().apply {
                addProperty("level", "warn")
                addProperty("disabled", false)
            })
            // sing-box 1.14 requires a domain resolver for proxy server hostnames.
            // Only the encrypted outbound's bootstrap hostname uses local DNS.
            root.add("dns", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "local")
                        addProperty("tag", "lic-bootstrap-dns")
                    })
                })
            })
            root.add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    // SOCKS-only: never expose an unauthenticated HTTP proxy.
                    // The live native session always provides random credentials.
                    addProperty("type", "socks")
                    addProperty("tag", LOCAL_SOCKS_TAG)
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", PROXY_PORT)
                    if (localPassword != null) {
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("username", "nlic")
                                addProperty("password", localPassword)
                            })
                        })
                    }
                })
            })
            val outbounds = JsonArray()
            outbound.addProperty("tag", PROXY_TAG)
            if (bay == Bay.ON) {
                outbound.addProperty("detour", BAY_TAG)
            }
            outbounds.add(outbound)
            if (bay == Bay.ON) {
                outbounds.add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", BAY_TAG)
                    addProperty("server", "127.0.0.1")
                    addProperty("server_port", bayPort)
                    addProperty("version", "5")
                })
            }
            root.add("outbounds", outbounds)
            root.add("route", JsonObject().apply {
                addProperty("final", PROXY_TAG)
                // Select the physical interface, not Android's new VPN interface.
                // The platform hook uses VpnService.protect(fd) for each
                // upstream dial; disabling this can loop a VPN into itself.
                addProperty("auto_detect_interface", true)
                addProperty("default_domain_resolver", "lic-bootstrap-dns")
            })
            Result.Compiled(root.toString(), protocol)
        } catch (_: Exception) {
            // Never include a URI, parsing error or native config in UI/logs:
            // they can contain credentials, private keys, or subscription IDs.
            Result.Unsupported("Invalid or unsupported connection parameters")
        }
    }

    /**
     * LIC-Auto stage 2: real sing-box URLTest across authenticated encrypted
     * outbounds. Tests run ONLY while the user has activated LIC-Core, and
     * existing established connections are never forcibly interrupted.
     *
     * A 204 test checks tunnel functionality/latency, not throughput.
     */
    fun compileAuto(
        nodes: List<SubscriptionNode>,
        bay: Bay = Bay.OFF,
        bayPort: Int = 1080,
        localPassword: String? = null,
    ): Result {
        if (nodes.isEmpty()) return Result.Unsupported("No subscription nodes")
        val candidates = nodes.distinctBy { it.id }.asSequence()
        val valid = candidates.mapNotNull { node ->
            val result = compile(node, bay, bayPort, localPassword)
            if (result !is Result.Compiled) return@mapNotNull null
            val root = JsonParser.parseString(result.config).asJsonObject
            node to root
        }.take(12).toList()
        if (valid.isEmpty()) {
            return Result.Unsupported("No compatible servers for LIC-Auto and LIC-Bay")
        }
        val root = valid.first().second.deepCopy()
        val outbounds = JsonArray()
        val tags = JsonArray()
        for ((node, config) in valid) {
            val outbound = config.getAsJsonArray("outbounds")[0].asJsonObject.deepCopy()
            val tag = "lic-node-${node.id.take(16)}"
            outbound.addProperty("tag", tag)
            tags.add(tag)
            outbounds.add(outbound)
        }
        if (bay == Bay.ON) {
            // Every TCP candidate shares ONE local ByeDPI outbound.
            outbounds.add(valid.first().second.getAsJsonArray("outbounds")[1].deepCopy())
        }
        outbounds.add(JsonObject().apply {
            addProperty("type", "urltest")
            addProperty("tag", "lic-auto")
            add("outbounds", tags)
            addProperty("url", "https://www.gstatic.com/generate_204")
            addProperty("interval", "10m")
            addProperty("tolerance", 120)
            addProperty("idle_timeout", "30m")
            addProperty("interrupt_exist_connections", false)
        })
        root.add("outbounds", outbounds)
        root.getAsJsonObject("route").addProperty("final", "lic-auto")
        return Result.Compiled(root.toString(), "URLTEST")
    }

    private fun decodeOutbound(node: SubscriptionNode): JsonObject? {
        val proto = node.protocol.uppercase()
        val core = JsonObject().apply {
            addProperty("type", when (proto) {
                "VLESS" -> "vless"
                "TROJAN" -> "trojan"
                "VMESS" -> "vmess"
                "SHADOWSOCKS" -> "shadowsocks"
                "HYSTERIA2", "HY2" -> "hysteria2"
                "TUIC" -> "tuic"
                else -> return null
            })
            addProperty("server", node.host)
            addProperty("server_port", node.port)
        }
        if (node.port !in 1..65535 || node.host.isBlank()) return null
        return when (proto) {
            "VLESS" -> vless(node.originalUri, core)
            "TROJAN" -> trojan(node.originalUri, core)
            "VMESS" -> vmess(node.originalUri, core)
            "SHADOWSOCKS" -> shadowsocks(node.originalUri, core)
            "HYSTERIA2", "HY2" -> hysteria2(node.originalUri, core)
            "TUIC" -> tuic(node.originalUri, core)
            else -> null
        }
    }

    private fun vless(source: String, out: JsonObject): JsonObject? {
        val uri = URI(source)
        val uuid = uri.userInfo ?: return null
        if (runCatching { UUID.fromString(uuid) }.isFailure) return null
        val query = args(uri.rawQuery)
        val security = query["security"] ?: "none"
        val network = query["type"] ?: "tcp"
        if (query["encryption"]?.let { it != "none" } == true) return null
        // VLESS itself provides no encryption. Do not advertise plaintext
        // VLESS as a protected VPN or accept an insecure TLS override.
        if (security == "none" || requestsInsecureTls(query)) return null
        out.addProperty("uuid", uuid)
        val flow = query["flow"].orEmpty()
        if (flow.isNotEmpty()) {
            if (flow != "xtls-rprx-vision" || network != "tcp" || security == "none") return null
            out.addProperty("flow", flow)
        }
        val tls = tls(
            security, query["sni"] ?: uri.host,
            query["fp"] ?: if (security == "reality") "chrome" else null,
            query["pbk"], query["sid"], query["alpn"],
        ) ?: return null
        if (security != "none") out.add("tls", tls)
        if (!applyTransport(network, query, out)) return null
        return out
    }

    private fun trojan(source: String, out: JsonObject): JsonObject? {
        val uri = URI(source)
        val password = uri.userInfo ?: return null
        if (password.isBlank()) return null
        val query = args(uri.rawQuery)
        if (requestsInsecureTls(query)) return null
        out.addProperty("password", password)
        val security = query["security"] ?: "tls"
        val tls = tls(security, query["sni"] ?: uri.host,
            query["fp"] ?: if (security == "reality") "chrome" else null, null,
            null, query["alpn"]) ?: return null
        if (security == "none") return null // Never downgrade Trojan TLS.
        out.add("tls", tls)
        if (!applyTransport(query["type"] ?: "tcp", query, out)) return null
        return out
    }

    private fun vmess(source: String, out: JsonObject): JsonObject? {
        val json = SubscriptionImport.decodeBase64Text(source.substringAfter("://")
            .substringBefore('#')) ?: return null
        val obj = JsonParser.parseString(json).asJsonObject
        val uuid = obj.get("id")?.asString ?: return null
        if (runCatching { UUID.fromString(uuid) }.isFailure) return null
        out.addProperty("uuid", uuid)
        val security = obj.get("scy")?.asString?.takeIf { it.isNotBlank() } ?: "auto"
        if (security !in setOf("auto", "aes-128-gcm", "chacha20-poly1305")) return null
        out.addProperty("security", security)
        val aid = obj.get("aid")?.asString?.toIntOrNull() ?: 0
        if (aid !in 0..1) return null
        out.addProperty("alter_id", aid)
        val tlsField = obj.get("tls")?.asString?.lowercase().orEmpty()
        // Unknown TLS settings must never silently become a cleartext transport.
        if (tlsField !in setOf("", "none", "tls")) return null
        if (listOf("allowInsecure", "insecure", "skip-cert-verify").any { key ->
                obj.get(key)?.asString?.trim()?.lowercase() in INSECURE_VALUES
            }) return null
        val tlsMode = if (tlsField == "tls") "tls" else "none"
        if (tlsMode == "tls") {
            val tls = tls(tlsMode, obj.get("sni")?.asString?.takeIf(String::isNotEmpty)
                ?: nodeHost(out), obj.get("fp")?.asString, null, null, obj.get("alpn")?.asString)
                ?: return null
            out.add("tls", tls)
        }
        val net = obj.get("net")?.asString?.takeIf(String::isNotBlank) ?: "tcp"
        val params = mapOf("path" to (obj.get("path")?.asString.orEmpty()),
            "host" to (obj.get("host")?.asString.orEmpty()))
        if (!applyTransport(net, params, out)) return null
        return out
    }

    private fun shadowsocks(source: String, out: JsonObject): JsonObject? {
        val uri = URI(source)
        val encoded = uri.rawUserInfo
        val credentials = if (!encoded.isNullOrBlank()) {
            SubscriptionImport.decodeBase64Text(encoded) ?: encoded
        } else {
            val decoded = SubscriptionImport.decodeBase64Text(
                source.substringAfter("://").substringBefore('#').substringBefore('?')
            ) ?: return null
            decoded.substringBeforeLast('@')
        }
        val method = credentials.substringBefore(':', "")
        val password = credentials.substringAfter(':', "")
        if (method !in setOf(
                "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
                "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
                "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305"
            ) || password.isEmpty()) return null
        if (args(uri.rawQuery).containsKey("plugin")) return null
        out.addProperty("method", method)
        out.addProperty("password", password)
        return out
    }

    private fun hysteria2(source: String, out: JsonObject): JsonObject? {
        val uri = URI(source)
        val password = uri.userInfo ?: return null
        if (password.isEmpty()) return null
        val query = args(uri.rawQuery)
        if (requestsInsecureTls(query)) return null
        out.addProperty("password", password)
        val tls = tls("tls", query["sni"] ?: uri.host, null, null, null,
            query["alpn"] ?: "h3") ?: return null
        out.add("tls", tls)
        val obfs = query["obfs"]
        if (obfs != null) {
            if (obfs != "salamander" || query["obfs-password"].isNullOrEmpty()) return null
            out.add("obfs", JsonObject().apply {
                addProperty("type", "salamander")
                addProperty("password", query["obfs-password"])
            })
        }
        return out
    }

    private fun tuic(source: String, out: JsonObject): JsonObject? {
        val uri = URI(source)
        val info = uri.userInfo ?: return null
        val uuid = info.substringBefore(':', "")
        val password = info.substringAfter(':', "")
        if (runCatching { UUID.fromString(uuid) }.isFailure || password.isEmpty()) return null
        val query = args(uri.rawQuery)
        if (requestsInsecureTls(query)) return null
        val congestion = query["congestion_control"] ?: query["congestionControl"] ?: "cubic"
        if (congestion !in setOf("cubic", "new_reno", "bbr")) return null
        out.addProperty("uuid", uuid)
        out.addProperty("password", password)
        out.addProperty("congestion_control", congestion)
        val tls = tls("tls", query["sni"] ?: uri.host, null, null, null,
            query["alpn"] ?: "h3") ?: return null
        out.add("tls", tls)
        return out
    }

    private fun applyTransport(network: String, query: Map<String, String>, out: JsonObject): Boolean {
        when (network.lowercase()) {
            "tcp" -> return true
            "ws" -> {
                out.add("transport", JsonObject().apply {
                    addProperty("type", "ws")
                    addProperty("path", query["path"]?.ifEmpty { "/" } ?: "/")
                    query["host"]?.takeIf(String::isNotBlank)?.let { host ->
                        add("headers", JsonObject().apply { addProperty("Host", host) })
                    }
                })
                return true
            }
            "grpc" -> {
                out.add("transport", JsonObject().apply {
                    addProperty("type", "grpc")
                    addProperty("service_name",
                        query["serviceName"] ?: query["service_name"] ?: "")
                })
                return true
            }
            else -> return false // Do not silently drop XHTTP/H2/KCP.
        }
    }

    private fun tls(
        security: String,
        sni: String?,
        fingerprint: String?,
        publicKey: String?,
        shortId: String?,
        alpn: String?,
    ): JsonObject? {
        if (security !in setOf("none", "tls", "reality")) return null
        if (security == "none") return JsonObject()
        if (sni.isNullOrBlank()) return null
        val tls = JsonObject().apply {
            addProperty("enabled", true)
            addProperty("server_name", sni)
            addProperty("insecure", false)
        }
        alpn?.split(',')?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)
            ?.let { list ->
                tls.add("alpn", JsonArray().apply { list.forEach { add(it.trim()) } })
            }
        fingerprint?.takeIf { it.isNotBlank() && it != "none" }?.let { fp ->
            if (fp.lowercase() !in setOf("chrome", "firefox", "safari", "ios", "random",
                    "edge", "360", "qq")) return null
            tls.add("utls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("fingerprint", fp.lowercase())
            })
        }
        if (security == "reality") {
            if (publicKey.isNullOrBlank() || publicKey.length !in 40..64) return null
            tls.add("reality", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("public_key", publicKey)
                addProperty("short_id", shortId.orEmpty())
            })
        }
        return tls
    }

    private val INSECURE_VALUES = setOf("1", "true", "yes", "on")

    private fun requestsInsecureTls(query: Map<String, String>): Boolean =
        listOf("allowInsecure", "insecure", "skip-cert-verify", "skipCertVerify").any { key ->
            query[key]?.trim()?.lowercase() in INSECURE_VALUES
        }

    private fun nodeHost(out: JsonObject) = out.get("server")?.asString.orEmpty()

    private fun args(raw: String?): Map<String, String> = raw.orEmpty()
        .split('&').filter { it.isNotBlank() }.associate { pair ->
            val a = pair.substringBefore('=')
            val b = pair.substringAfter('=', "")
            URLDecoder.decode(a, "UTF-8") to URLDecoder.decode(b, "UTF-8")
        }
}
