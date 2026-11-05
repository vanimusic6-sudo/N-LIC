package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Explicit Xray-core JSON compiler. This is NOT sing-box JSON with a new
 * engine name: Xray uses different inbound, outbound and transport schemas.
 *
 * Initial experimental compatibility: VLESS (TLS/REALITY), VMess, Trojan,
 * Shadowsocks and wg-quick WireGuard. Unsupported profiles fail closed.
 */
internal object XrayConfigCompiler {
    const val SOCKS_PORT = 11089
    const val PROXY_TAG = "nlic-xray"
    const val BAY_TAG = "nlic-bay"
    const val BAY_PORT = 11088

    sealed class Result {
        data class Compiled(val json: String, val protocol: String) : Result()
        data class Unsupported(val reason: String) : Result()
    }

    fun compile(
        node: SubscriptionNode,
        auth: LocalSocksAuth,
        bayPort: Int? = null,
        realityClientFingerprint: String? = null,
    ): Result {
        if (auth.username != "nlic" || auth.password.length !in 24..128 ||
            !auth.password.all { it in "0123456789abcdef" }) {
            return Result.Unsupported("Invalid private SOCKS credentials")
        }
        if (realityClientFingerprint != null &&
            realityClientFingerprint !in setOf("chrome", "firefox")) {
            return Result.Unsupported("Unsupported REALITY TLS client fingerprint")
        }
        if (bayPort != null) {
            if (bayPort !in 1..65535 || bayPort == SOCKS_PORT) {
                return Result.Unsupported("Invalid local ByeDPI relay port")
            }
            if (!LICBay.plan(node).supported) {
                return Result.Unsupported("LIC-Bay cannot relay this protocol")
            }
        }
        return try {
            val outbound = if (node.protocol == "WIREGUARD") {
                wireguard(node)
            } else {
                val compiled = SingBoxConfigCompiler.compile(node)
                    as? SingBoxConfigCompiler.Result.Compiled
                    ?: return Result.Unsupported("Unsupported source protocol")
                val singOutbound = JsonParser.parseString(compiled.config)
                    .asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject
                convert(singOutbound)
            } ?: return Result.Unsupported("Unsupported Xray transport")

            // LIC-Assist changes only Xray's uTLS ClientHello, never the
            // VPN server, credentials, VLESS flow, SNI or REALITY auth.
            // This does not emulate the entire Firefox browser.
            if (realityClientFingerprint != null &&
                outbound.get("protocol")?.asString == "vless") {
                val streamSettings = outbound.getAsJsonObject("streamSettings")
                if (streamSettings?.get("network")?.asString == "raw" &&
                    streamSettings.get("security")?.asString == "reality") {
                    val reality = streamSettings.getAsJsonObject("realitySettings")
                        ?: return Result.Unsupported("Missing REALITY authentication")
                    reality.addProperty("fingerprint", realityClientFingerprint)
                }
            }
            outbound.addProperty("tag", PROXY_TAG)
            if (bayPort != null) {
                // DialerProxy intercepts Xray's actual socket creation for
                // both TCP and UDP. No opportunistic direct fallback.
                // For WireGuard the Go bind uses DialSystem(SocketSettings);
                // the Xray SOCKS outbound performs UDP ASSOCIATE.
                outbound.getAsJsonObject("streamSettings")?.let {
                    it.add("sockopt", JsonObject().apply {
                        addProperty("dialerProxy", BAY_TAG)
                    })
                } ?: outbound.add("streamSettings", JsonObject().apply {
                    addProperty("network", "raw")
                    add("sockopt", JsonObject().apply {
                        addProperty("dialerProxy", BAY_TAG)
                    })
                })
            }
            val root = JsonObject().apply {
                add("log", JsonObject().apply { addProperty("loglevel", "warning") })
                // AndroidLibXrayLite obtains stats.Manager at startup; supply
                // the feature explicitly instead of relying on a Go nil cast.
                add("stats", JsonObject())
                add("inbounds", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("tag", "nlic-local-socks")
                        addProperty("listen", "127.0.0.1")
                        addProperty("port", SOCKS_PORT)
                        addProperty("protocol", "socks")
                        add("settings", JsonObject().apply {
                            addProperty("auth", "password")
                            addProperty("udp", true)
                            addProperty("ip", "127.0.0.1")
                            add("accounts", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("user", auth.username)
                                    addProperty("pass", auth.password)
                                })
                            })
                        })
                    })
                })
                add("outbounds", JsonArray().apply {
                    // First outbound is always encrypted VPN; never the relay.
                    add(outbound)
                    if (bayPort != null) add(JsonObject().apply {
                        addProperty("protocol", "socks")
                        addProperty("tag", BAY_TAG)
                        add("settings", JsonObject().apply {
                            add("servers", JsonArray().apply { add(JsonObject().apply {
                                addProperty("address", "127.0.0.1")
                                addProperty("port", bayPort)
                            }) })
                        })
                    })
                })
            }
            Result.Compiled(root.toString(), node.protocol)
        } catch (_: Exception) {
            Result.Unsupported("Invalid Xray connection parameters")
        }
    }

    private fun convert(src: JsonObject): JsonObject? {
        val type = src.get("type")?.asString ?: return null
        val server = src.get("server")?.asString ?: return null
        val port = src.get("server_port")?.asInt ?: return null
        if (server.isBlank() || port !in 1..65535) return null
        val outbound = JsonObject()
        val settings = JsonObject()
        val serverObject = JsonObject().apply {
            addProperty("address", server)
            addProperty("port", port)
        }
        when (type) {
            "vless", "vmess" -> {
                val id = src.get("uuid")?.asString ?: return null
                val user = JsonObject().apply {
                    addProperty("id", id)
                    if (type == "vless") {
                        addProperty("encryption", "none")
                        src.get("flow")?.asString?.takeIf { it.isNotBlank() }?.let {
                            addProperty("flow", it)
                        }
                    } else {
                        addProperty("security",
                            src.get("security")?.asString ?: "auto")
                        addProperty("alterId", src.get("alter_id")?.asInt ?: 0)
                    }
                }
                serverObject.add("users", JsonArray().apply { add(user) })
                settings.add("vnext", JsonArray().apply { add(serverObject) })
            }
            "trojan", "shadowsocks" -> {
                val password = src.get("password")?.asString ?: return null
                serverObject.addProperty("password", password)
                if (type == "shadowsocks")
                    serverObject.addProperty("method",
                        src.get("method")?.asString ?: return null)
                settings.add("servers", JsonArray().apply { add(serverObject) })
            }
            else -> return null
        }
        outbound.addProperty("protocol", type)
        outbound.add("settings", settings)
        val stream = stream(src) ?: return null
        if (stream.size() > 0) outbound.add("streamSettings", stream)
        return outbound
    }

    private fun stream(src: JsonObject): JsonObject? {
        val transport = src.getAsJsonObject("transport")
        val tls = src.getAsJsonObject("tls")
        val stream = JsonObject()
        val network = transport?.get("type")?.asString ?: "tcp"
        val targetNetwork = when (network) {
            "tcp" -> "raw"
            "ws", "grpc", "httpupgrade" -> network
            // Legacy HTTP/2 transport is removed in Xray 26.x.
            // Do not silently turn it into RAW or claim support.
            else -> return null // Never pretend QUIC or an unknown transport works.
        }
        stream.addProperty("network", targetNetwork)
        when (network) {
            "ws" -> stream.add("wsSettings", JsonObject().apply {
                addProperty("path", transport?.get("path")?.asString ?: "/")
                transport?.getAsJsonObject("headers")?.get("Host")
                    ?.asString?.let { addProperty("host", it) }
            })
            "grpc" -> stream.add("grpcSettings", JsonObject().apply {
                addProperty("serviceName",
                    transport?.get("service_name")?.asString ?: "")
            })
            "httpupgrade" -> stream.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", transport?.get("path")?.asString ?: "/")
                transport?.get("host")?.asString?.let { addProperty("host", it) }
            })
            "http" -> stream.add("httpSettings", JsonObject().apply {
                addProperty("path", transport?.get("path")?.asString ?: "/")
                transport?.getAsJsonArray("host")?.let { add("host", it.deepCopy()) }
            })
        }
        if (tls != null && tls.get("enabled")?.asBoolean == true) {
            if (tls.get("insecure")?.asBoolean == true) return null
            val reality = tls.getAsJsonObject("reality")
            val security = if (reality?.get("enabled")?.asBoolean == true)
                "reality" else "tls"
            // Xray REALITY supports RAW, XHTTP and gRPC. XHTTP is not
            // translated by this compiler yet; WS/HTTPUpgrade are invalid.
            if (security == "reality" && targetNetwork !in setOf("raw", "grpc"))
                return null
            stream.addProperty("security", security)
            val tlsSettings = JsonObject().apply {
                addProperty("serverName", tls.get("server_name")?.asString ?: return null)
                addProperty("allowInsecure", false)
                tls.getAsJsonArray("alpn")?.let { add("alpn", it.deepCopy()) }
                tls.getAsJsonObject("utls")?.get("fingerprint")?.asString?.let {
                    addProperty("fingerprint", it)
                }
            }
            if (security == "reality") {
                val sid = reality?.get("short_id")?.asString.orEmpty()
                if (sid.length > 16 || sid.length % 2 != 0 ||
                    sid.any { it !in "0123456789abcdefABCDEF" }) return null
                val realitySettings = JsonObject().apply {
                    addProperty("serverName",
                        tls.get("server_name")?.asString ?: return null)
                    addProperty("fingerprint",
                        tls.getAsJsonObject("utls")?.get("fingerprint")?.asString ?: "chrome")
                    // Xray 26.x accepts "password" as the REALITY public key.
                    addProperty("password",
                        reality?.get("public_key")?.asString ?: return null)
                    addProperty("shortId", reality.get("short_id")?.asString ?: "")
                }
                stream.add("realitySettings", realitySettings)
            } else {
                stream.add("tlsSettings", tlsSettings)
            }
        }
        return stream
    }

    private fun wireguard(node: SubscriptionNode): JsonObject? {
        val wg = VPNFileImport.readWireGuard(node)?.endpoint ?: return null
        val peer = wg.getAsJsonArray("peers")?.firstOrNull()?.asJsonObject ?: return null
        val address = wg.getAsJsonArray("address") ?: return null
        return JsonObject().apply {
            addProperty("protocol", "wireguard")
            add("settings", JsonObject().apply {
                addProperty("secretKey",
                    wg.get("private_key")?.asString ?: return null)
                add("address", JsonArray().apply {
                    address.forEach { add(it.asString.substringBefore('/')) }
                })
                addProperty("mtu", wg.get("mtu")?.asInt ?: 1400)
                addProperty("noKernelTun", true)
                add("peers", JsonArray().apply {
                    add(JsonObject().apply {
                        val host = peer.get("address")?.asString ?: return null
                        val port = peer.get("port")?.asInt ?: return null
                        addProperty("endpoint",
                            if (host.contains(':')) "[$host]:$port" else "$host:$port")
                        addProperty("publicKey",
                            peer.get("public_key")?.asString ?: return null)
                        peer.get("pre_shared_key")?.asString?.let {
                            addProperty("preSharedKey", it)
                        }
                        peer.getAsJsonArray("allowed_ips")?.let {
                            add("allowedIPs", it.deepCopy())
                        }
                        addProperty("keepAlive",
                            peer.get("persistent_keepalive_interval")?.asInt ?: 25)
                    })
                })
            })
        }
    }
}
