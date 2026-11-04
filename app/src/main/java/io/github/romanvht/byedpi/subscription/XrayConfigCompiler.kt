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
    ): Result {
        if (auth.username != "nlic" || auth.password.length !in 24..128 ||
            !auth.password.all { it in "0123456789abcdef" }) {
            return Result.Unsupported("Invalid private SOCKS credentials")
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

    const val FORK_BRIDGE_TAG = "nlic-fork-bridge"

    /**
     * Two authenticated Xray transports, not packet fragmentation:
     * Android TUN -> local SOCKS -> EXIT (tunneled via BRIDGE) -> Internet.
     * The peer at BRIDGE can see the EXIT address. Nothing leaves through a
     * direct fallback, even if the bridge fails after startup.
     *
     * Only TLS/REALITY-protected TCP-compatible transports are considered.
     * UDP/WireGuard/QUIC bridging cannot be assumed to work over TCP SOCKS.
     */
    fun compileChain(
        exit: SubscriptionNode,
        bridge: SubscriptionNode,
        auth: LocalSocksAuth,
    ): Result {
        if (exit.id == bridge.id || exit.host.equals(bridge.host, ignoreCase = true)) {
            return Result.Unsupported("Chain endpoints must be distinct")
        }
        val first = compile(exit, auth) as? Result.Compiled
            ?: return Result.Unsupported("Exit protocol is not supported")
        val second = compile(bridge, auth) as? Result.Compiled
            ?: return Result.Unsupported("Bridge protocol is not supported")
        return try {
            val root = JsonParser.parseString(first.json).asJsonObject
            val exitOutbound = root.getAsJsonArray("outbounds")[0].asJsonObject
            val bridgeOutbound = JsonParser.parseString(second.json).asJsonObject
                .getAsJsonArray("outbounds")[0].asJsonObject.deepCopy()

            fun approved(outbound: JsonObject): Boolean {
                if (outbound.get("protocol")?.asString !in
                    setOf("vless", "vmess", "trojan")) return false
                val stream = outbound.getAsJsonObject("streamSettings") ?: return false
                if (stream.get("network")?.asString !in
                    setOf("raw", "ws", "grpc", "httpupgrade", "xhttp")) return false
                return stream.get("security")?.asString in setOf("tls", "reality")
            }
            if (!approved(exitOutbound) || !approved(bridgeOutbound)) {
                return Result.Unsupported("Chain requires two encrypted TCP transports")
            }
            // Xray dialerProxy acts on the outbound socket, not on the user's
            // payload. No free-form routing fields are accepted from imports.
            exitOutbound.getAsJsonObject("streamSettings").add("sockopt",
                JsonObject().apply { addProperty("dialerProxy", FORK_BRIDGE_TAG) })
            bridgeOutbound.addProperty("tag", FORK_BRIDGE_TAG)
            root.getAsJsonArray("outbounds").add(bridgeOutbound)
            Result.Compiled(root.toString(), "CHAIN")
        } catch (_: Exception) {
            Result.Unsupported("Could not construct encrypted chain")
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
