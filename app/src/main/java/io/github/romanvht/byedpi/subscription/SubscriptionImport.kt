package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import androidx.annotation.Keep
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Keep
internal data class SubscriptionNode(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("protocol") val protocol: String,
    @SerializedName("host") val host: String,
    @SerializedName("port") val port: Int,
    @SerializedName("originalUri") val originalUri: String,
    @SerializedName("group") val group: String = "Imported",
)

@Keep
internal data class SubscriptionGroup(
    @SerializedName("title") val title: String,
    @SerializedName("url") val url: String,
    @SerializedName("nodes") val nodes: List<SubscriptionNode>,
    @SerializedName("refreshedAt") val refreshedAt: Long,
)

@Keep
internal data class SubscriptionCatalog(
    @SerializedName("groups") val groups: List<SubscriptionGroup> = emptyList(),
    @SerializedName("manual") val manual: List<SubscriptionNode> = emptyList(),
    @SerializedName("selectedNodeId") val selectedNodeId: String? = null,
) {
    val allNodes: List<SubscriptionNode>
        get() = (manual + groups.flatMap { it.nodes }).distinctBy { it.id }
}

/**
 * Import-time structural parser, not a VPN protocol engine.
 * It preserves each original config for a future audited sing-box adapter.
 * No URI with embedded credentials should be logged or put in exceptions.
 */
internal object SubscriptionImport {
    const val MAX_NODES = 300
    private const val MAX_TEXT_LENGTH = 512 * 1024

    fun parse(content: String, group: String = "Imported"): List<SubscriptionNode> {
        require(content.length <= MAX_TEXT_LENGTH) { "Import exceeds 512 KiB" }
        val cleaned = content.trim()
        // Some subscription providers wrap an entire Base64 payload over
        // multiple lines; a newline alone does NOT imply plain URI lines.
        val anyUriLine = cleaned.lines().any { candidate ->
            listOf("vless://", "vmess://", "trojan://", "ss://",
                "hysteria2://", "hy2://", "tuic://").any {
                candidate.trim().startsWith(it, true)
            }
        }
        val expanded = if (anyUriLine) cleaned else {
            decodeBase64Text(cleaned)?.takeIf { decoded ->
                decoded.contains("://")
            } ?: cleaned
        }

        return expanded.lines().asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(MAX_NODES)
            .mapNotNull { parseNode(it, group) }
            .distinctBy { it.id }
            .toList()
    }

    fun parseNode(line: String, group: String = "Imported"): SubscriptionNode? {
        if (line.length !in 10..8192 || line.contains('\n') || line.contains('\r')) return null
        val scheme = line.substringBefore("://", "").lowercase()
        return try {
            when (scheme) {
                "vless", "trojan", "hysteria2", "hy2", "tuic" -> {
                    val uri = URI(line)
                    val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
                    val port = uri.port
                    if (!validEndpoint(host, port)) return null
                    if (uri.rawUserInfo.isNullOrBlank()) return null
                    makeNode(line, scheme.uppercase(), host, port, uri.rawFragment, group)
                }
                "vmess" -> {
                    val raw = line.substringAfter("://").substringBefore('#')
                    val json = decodeBase64Text(raw) ?: return null
                    val obj = JsonParser.parseString(json).asJsonObject
                    val host = obj.get("add")?.asString ?: return null
                    val port = obj.get("port")?.asString?.toIntOrNull() ?: return null
                    if (!validEndpoint(host, port)) return null
                    makeNode(line, "VMESS", host, port,
                        obj.get("ps")?.asString, group, fragmentEncoded = false)
                }
                "ss" -> {
                    // SIP002: ss://base64(method:pass)@host:port#tag.
                    // Legacy: ss://base64(method:pass@host:port)#tag.
                    val uri = URI(line)
                    val tag = uri.rawFragment
                    val hostPort = if (uri.host != null) {
                        uri.host to uri.port
                    } else {
                        val legacy = line.substringAfter("://").substringBefore('#')
                            .substringBefore('?')
                        val decoded = decodeBase64Text(legacy) ?: return null
                        val endpoint = decoded.substringAfterLast('@', "")
                        val parsed = URI("ss://$endpoint")
                        parsed.host to parsed.port
                    }
                    val host = hostPort.first ?: return null
                    val port = hostPort.second
                    if (!validEndpoint(host, port)) return null
                    makeNode(line, "SHADOWSOCKS", host, port, tag, group)
                }
                else -> null
            }
        } catch (_: Exception) {
            null // Secrets may be embedded in the malformed line.
        }
    }

    private fun makeNode(
        original: String,
        protocol: String,
        host: String,
        port: Int,
        fragment: String?,
        group: String,
        fragmentEncoded: Boolean = true,
    ): SubscriptionNode {
        val label = if (fragmentEncoded) {
            try {
                URLDecoder.decode(fragment?.replace("+", "%2B").orEmpty(), "UTF-8")
            } catch (_: Exception) { "" }
        } else fragment.orEmpty()
        val safeLabel = label.filter { it >= ' ' && it != '\u007f' }.trim().take(70)
        val id = MessageDigest.getInstance("SHA-256")
            .digest(original.toByteArray(StandardCharsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
        return SubscriptionNode(id, safeLabel.ifEmpty { "$protocol · $host" },
            protocol, host, port, original, group)
    }

    private fun validEndpoint(host: String, port: Int): Boolean {
        if (host.isBlank() || host.length > 253 || port !in 1..65535) return false
        return host.none { it.isWhitespace() || it == '/' || it == '\\' || it.code < 32 }
    }

    /** Pure JVM + Android compatible Base64 (standard and URL-safe). */
    fun decodeBase64Text(source: String): String? {
        val text = source.filterNot(Char::isWhitespace)
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH * 2) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val result = ArrayList<Byte>(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c == '=') break
            val normalized = when(c) { '-' -> '+'; '_' -> '/'; else -> c }
            val n = alphabet.indexOf(normalized)
            if (n < 0) return null
            acc = (acc shl 6) or n
            bits += 6
            if (bits >= 8) {
                bits -= 8
                result.add(((acc shr bits) and 255).toByte())
            }
        }
        return try { String(result.toByteArray(), StandardCharsets.UTF_8) }
        catch (_: Exception) { null }
    }
}
