package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

/**
 * Import-only XHTTP outbound for Xray 26.x. This is a genuinely different
 * HTTP-family transport, NOT another TLS first-flight splitter.
 *
 * Only a provider's explicit XHTTP node is accepted. We never silently
 * convert a RAW/TCP node to XHTTP: the server must expose an XHTTP inbound.
 * All sensitive values remain in the encrypted SubscriptionVault.
 */
internal object XrayXhttpCompiler {
    private const val MAX_EXTRA_LENGTH = 4096
    private const val MAX_PATH_LENGTH = 512
    private val modes = setOf("auto", "packet-up", "stream-up", "stream-one")
    private val fingerprints = setOf("chrome", "firefox", "safari")
    private val allowedAlpn = setOf("h2", "http/1.1", "h3")

    internal fun isXhttp(node: SubscriptionNode): Boolean = runCatching {
        if (node.protocol != "VLESS") return@runCatching false
        if (node.originalUri.startsWith("nlicjson://")) {
            VPNFileImport.parseSecureJson(node)?.getAsJsonObject("transport")
                ?.get("type")?.asString?.lowercase(Locale.ROOT) == "xhttp"
        } else {
            val uri = URI(node.originalUri)
            uri.scheme?.lowercase(Locale.ROOT) == "vless" &&
                query(uri)["type"]?.lowercase(Locale.ROOT) == "xhttp"
        }
    }.getOrDefault(false)

    internal fun compile(node: SubscriptionNode): JsonObject? = runCatching {
        if (!isXhttp(node)) return@runCatching null
        val source = if (node.originalUri.startsWith("nlicjson://")) {
            sourceFromJson(node)
        } else {
            sourceFromUri(node)
        } ?: return@runCatching null
        outbound(node, source)
    }.getOrNull()

    private data class Source(
        val uuid: String,
        val security: String,
        val serverName: String,
        val fingerprint: String,
        val publicKey: String?,
        val shortId: String,
        val spiderX: String,
        val path: String,
        val host: String?,
        val mode: String,
        val extra: JsonObject?,
        val alpn: List<String>,
        val flow: String,
    )

    private fun sourceFromUri(node: SubscriptionNode): Source? {
        val uri = URI(node.originalUri)
        if (uri.scheme?.lowercase(Locale.ROOT) != "vless" ||
            uri.host != node.host || uri.port != node.port) return null
        val q = query(uri)
        if (q["type"]?.lowercase(Locale.ROOT) != "xhttp" ||
            q["insecure"] == "1" || q["insecure"] == "true" ||
            q["allowinsecure"] == "1" || q["allowinsecure"] == "true" ||
            q["skip-cert-verify"] == "1" || q["skip-cert-verify"] == "true")
            return null
        if (q["encryption"].orEmpty().ifBlank { "none" } != "none") return null
        val sec = q["security"]?.lowercase(Locale.ROOT) ?: return null
        if (sec !in setOf("reality", "tls")) return null
        val uuid = uri.userInfo ?: return null
        val serverName = q["sni"] ?: q["servername"] ?: return null
        return Source(
            uuid = uuid,
            security = sec,
            serverName = serverName,
            fingerprint = q["fp"] ?: "chrome",
            publicKey = q["pbk"],
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"] ?: "/",
            path = q["path"] ?: "/",
            host = q["host"]?.ifBlank { null },
            mode = q["mode"] ?: "auto",
            extra = parseExtra(q["extra"]),
            alpn = q["alpn"]?.split(',')?.filter(String::isNotBlank) ?: emptyList(),
            flow = q["flow"].orEmpty(),
        ).takeIf { q["extra"] == null || it.extra != null }
    }

    private fun sourceFromJson(node: SubscriptionNode): Source? {
        val obj = VPNFileImport.parseSecureJson(node) ?: return null
        if (obj.get("type")?.asString != "vless") return null
        val transport = obj.getAsJsonObject("transport") ?: return null
        if (transport.get("type")?.asString != "xhttp") return null
        val tls = obj.getAsJsonObject("tls") ?: return null
        if (tls.get("enabled")?.asBoolean != true) return null
        val reality = tls.getAsJsonObject("reality")
        val isReality = reality?.get("enabled")?.asBoolean == true
        val extraElement = transport.get("extra")
        val extra = if (extraElement != null && !extraElement.isJsonNull) {
            if (!extraElement.isJsonObject) return null
            validateExtra(extraElement.asJsonObject)
        } else null
        if (extraElement != null && !extraElement.isJsonNull && extra == null) return null
        return Source(
            uuid = obj.get("uuid")?.asString ?: return null,
            security = if (isReality) "reality" else "tls",
            serverName = tls.get("server_name")?.asString ?: return null,
            fingerprint = tls.getAsJsonObject("utls")?.get("fingerprint")?.asString ?: "chrome",
            publicKey = reality?.get("public_key")?.asString,
            shortId = reality?.get("short_id")?.asString.orEmpty(),
            spiderX = reality?.get("spider_x")?.asString ?: "/",
            path = transport.get("path")?.asString ?: "/",
            host = transport.get("host")?.asString?.ifBlank { null },
            mode = transport.get("mode")?.asString ?: "auto",
            extra = extra,
            alpn = tls.getAsJsonArray("alpn")?.map { it.asString } ?: emptyList(),
            flow = obj.get("flow")?.asString.orEmpty(),
        )
    }

    private fun outbound(node: SubscriptionNode, source: Source): JsonObject? {
        val uuid = runCatching { UUID.fromString(source.uuid) }.getOrNull() ?: return null
        if (uuid.toString() != source.uuid.lowercase(Locale.ROOT)) return null
        if (node.port !in 1..65535 || !safeHost(node.host) ||
            !safeHost(source.serverName) ||
            source.fingerprint !in fingerprints ||
            source.mode !in modes || source.flow.isNotEmpty() ||
            source.alpn.any { it !in allowedAlpn }) return null
        if (!safePath(source.path) || !safePath(source.spiderX) ||
            (source.host != null && !safeHost(source.host))) return null
        if (source.security == "reality") {
            val key = source.publicKey ?: return null
            if (!key.matches(Regex("[A-Za-z0-9_-]{43}")) ||
                source.shortId.length > 16 ||
                source.shortId.length % 2 != 0 ||
                source.shortId.any { it !in "0123456789abcdefABCDEF" })
                return null
        }
        return JsonObject().apply {
            addProperty("protocol", "vless")
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", node.host)
                        addProperty("port", node.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", source.uuid)
                                addProperty("encryption", "none")
                            })
                        })
                    })
                })
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "xhttp")
                addProperty("security", source.security)
                add("xhttpSettings", JsonObject().apply {
                    addProperty("path", source.path)
                    source.host?.let { addProperty("host", it) }
                    addProperty("mode", source.mode)
                    source.extra?.let { add("extra", it.deepCopy()) }
                })
                if (source.security == "reality") {
                    add("realitySettings", JsonObject().apply {
                        addProperty("serverName", source.serverName)
                        addProperty("fingerprint", source.fingerprint)
                        addProperty("password", source.publicKey)
                        addProperty("shortId", source.shortId)
                        addProperty("spiderX", source.spiderX)
                    })
                } else {
                    add("tlsSettings", JsonObject().apply {
                        addProperty("serverName", source.serverName)
                        addProperty("allowInsecure", false)
                        addProperty("fingerprint", source.fingerprint)
                        if (source.alpn.isNotEmpty()) {
                            add("alpn", JsonArray().apply {
                                source.alpn.forEach { add(it) }
                            })
                        }
                    })
                }
            })
        }
    }

    /**
     * Deliberately limited XHTTP extras. Unrecognized nested instructions
     * are rejected, not silently discarded. In particular, provider-defined
     * downloadSettings and headers cannot change the upstream destination.
     */
    private fun parseExtra(value: String?): JsonObject? {
        if (value == null) return null
        if (value.length > MAX_EXTRA_LENGTH) return null
        val obj = runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull()
            ?: return null
        return validateExtra(obj)
    }

    private fun validateExtra(input: JsonObject): JsonObject? {
        val allowed = setOf("xPaddingBytes", "scMaxEachPostBytes", "scMinPostsIntervalMs")
        if (input.entrySet().any { it.key !in allowed }) return null
        return runCatching {
            JsonObject().apply {
                input.get("xPaddingBytes")?.let {
                    val v = it.asString
                    val match = Regex("([0-9]{1,4})-([0-9]{1,4})").matchEntire(v)
                        ?: return null
                    val low = match.groupValues[1].toInt()
                    val high = match.groupValues[2].toInt()
                    if (low > high || high > 3000) return null
                    addProperty("xPaddingBytes", v)
                }
                input.get("scMaxEachPostBytes")?.let {
                    val n = it.asInt
                    if (n !in 1024..2_000_000) return null
                    addProperty("scMaxEachPostBytes", n)
                }
                input.get("scMinPostsIntervalMs")?.let {
                    val n = it.asInt
                    if (n !in 0..2000) return null
                    addProperty("scMinPostsIntervalMs", n)
                }
            }
        }.getOrNull()
    }

    private fun safePath(value: String): Boolean =
        value.length in 1..MAX_PATH_LENGTH && value.startsWith("/") &&
            !value.startsWith("//") &&
            value.none { it == '\\' || it.code < 32 || it == '#' }

    private fun safeHost(value: String): Boolean =
        value.length in 1..253 && value.isNotBlank() &&
            value.none { it.isWhitespace() || it == '/' || it == '\\' ||
                it == '@' || it.code < 32 }

    private fun query(uri: URI): Map<String, String> =
        uri.rawQuery?.split('&')?.take(50)?.mapNotNull { part ->
            val key = part.substringBefore('=')
            if (key.isEmpty()) return@mapNotNull null
            val name = URLDecoder.decode(key, "UTF-8").lowercase(Locale.ROOT)
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            name to value
        }?.toMap() ?: emptyMap()
}
