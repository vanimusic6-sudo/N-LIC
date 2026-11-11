package io.github.romanvht.byedpi.subscription

import java.net.URI
import java.util.Locale

/**
 * Data-only, fail-closed importer for the AWG 1.x / 2.x / 3.1 wg-quick format.
 * AWG is NOT interchangeable with the standard WireGuard/Xray/sing-box core.
 *
 * This module deliberately does not start VPN sockets. A matching native AWG
 * 3.1 runtime is required before an imported profile can be connected.
 */
internal object AmneziaProfileParser {
    enum class Version { LEGACY, V2, V31 }

    data class Profile(
        val version: Version,
        val host: String,
        val port: Int,
        val rawConfig: String,
        val obfuscationEnabled: Boolean,
    )

    private val awgKeys = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "headerprotectionkey", "contentpaddingaddition",
        "rekeyaftertime", "rekeytimeout", "rejectaftertime",
        "keepalivetimeout", "maxhandshakeattempts",
        "randomtrailers", "disablecookies"
    )
    private val basicInterface = setOf("privatekey", "address", "dns", "mtu", "listenport")
    private val basicPeer = setOf("publickey", "presharedkey", "endpoint",
        "allowedips", "persistentkeepalive")
    private val numeric = Regex("([0-9]{1,10})(?:-([0-9]{1,10}))?")
    private val base64Alphabet =
        Regex("[A-Za-z0-9+/]{43}=")
    private val cidr = Regex("([0-9A-Fa-f:.]+)/([0-9]{1,3})")

    fun looksLikeAmnezia(text: String): Boolean {
        if (text.length > 65_536) return false
        return text.lineSequence().any { line ->
            val trimmed = line.substringBefore('#').trim()
            val key = trimmed.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            key in awgKeys && '=' in trimmed
        }
    }

    fun parse(text: String): Profile? {
        return try {
        if (text.length !in 60..65_536 || !looksLikeAmnezia(text)) return null
        val iface = linkedMapOf<String, String>()
        val peer = linkedMapOf<String, String>()
        var active: MutableMap<String, String>? = null
        var seenInterface = 0
        var seenPeer = 0
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith(';')) continue
            if (line.equals("[Interface]", true)) {
                if (++seenInterface != 1 || seenPeer > 0) return null
                active = iface
                continue
            }
            if (line.equals("[Peer]", true)) {
                if (seenInterface != 1 || ++seenPeer != 1) return null
                active = peer
                continue
            }
            if (line.startsWith('[')) return null
            val map = active ?: return null
            val key = line.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            val value = line.substringAfter('=', "").trim()
            val allowed = if (map === iface) basicInterface + awgKeys else basicPeer
            if (key !in allowed || key in map || value.isEmpty() ||
                value.length > 2048 || value.any { it.code < 32 }) return null
            map[key] = value
        }
        if (seenPeer != 1) return null
        if (!validBase64Key(iface["privatekey"]) ||
            !validBase64Key(peer["publickey"])) return null
        if (peer["presharedkey"]?.let { !validBase64Key(it) } == true) return null
        if (iface["address"].orEmpty().split(',').any { !validCidr(it.trim()) }) return null
        val allowedIps = peer["allowedips"].orEmpty().split(',').map { it.trim() }
        if ("0.0.0.0/0" !in allowedIps || allowedIps.any { !validCidr(it) }) return null
        if (iface["address"].orEmpty().contains(':') && "::/0" !in allowedIps) return null
        if (iface["dns"]?.split(',')?.any { !validDnsLiteral(it.trim()) } == true) return null
        if (iface["mtu"]?.toIntOrNull()?.let { it !in 1280..1500 } == true) return null
        if (iface["listenport"]?.toIntOrNull()?.let { it !in 0..65535 } == true) return null
        if (peer["persistentkeepalive"]?.toIntOrNull()?.let { it !in 0..120 } == true) return null
        val endpoint = peer["endpoint"] ?: return null
        val url = URI("awg://$endpoint")
        val host = url.host ?: return null
        if (host.length !in 1..253 || host.any {
            it.isWhitespace() || it in "/\\@" || it.code < 33
        } || url.port !in 1..65535 ||
            url.userInfo != null || url.path.isNotEmpty() || url.query != null)
            return null

        if (!validUnsigned(iface["jc"], 128) ||
            !validUnsigned(iface["jmin"], 1280) ||
            !validUnsigned(iface["jmax"], 1280) ||
            !validUnsigned(iface["s1"], 1280) ||
            !validUnsigned(iface["s2"], 1280) ||
            !validUnsigned(iface["s3"], 1280) ||
            !validUnsigned(iface["s4"], 1280)) return null
        val jmin = iface["jmin"]?.toIntOrNull() ?: 0
        val jmax = iface["jmax"]?.toIntOrNull() ?: 0
        if (jmin > jmax) return null

        for (key in listOf("h1", "h2", "h3", "h4")) {
            if (!validRange(iface[key], 0xFFFF_FFFFL)) return null
        }
        for (key in listOf("contentpaddingaddition", "rekeyaftertime",
            "rekeytimeout", "rejectaftertime", "keepalivetimeout",
            "maxhandshakeattempts")) {
            val upper = if (key == "contentpaddingaddition") 1500L else 3600L
            if (!validRange(iface[key], upper)) return null
        }
        for (key in listOf("randomtrailers", "disablecookies")) {
            if (iface[key]?.lowercase(Locale.ROOT) !in setOf(null, "on", "off"))
                return null
        }
        // CPS strings are controlled by the provider. Keep them byte-for-byte,
        // but reject multiline, long or shell-like material instead of executing it.
        for (key in listOf("i1", "i2", "i3", "i4", "i5")) {
            val value = iface[key] ?: continue
            if (value.length > 1024 || value.any { it.code !in 33..126 }) return null
        }

        val hpKey = iface["headerprotectionkey"]
        val v31 = hpKey != null ||
            listOf("contentpaddingaddition", "rekeyaftertime",
                "rekeytimeout", "rejectaftertime", "keepalivetimeout",
                "maxhandshakeattempts", "randomtrailers", "disablecookies")
                .any { it in iface }
        if (hpKey != null) {
            if (!validBase64Key(hpKey)) return null
            // Header protection uses first 12 bytes of S1-S4 for its nonce.
            if (listOf("s1", "s2", "s3", "s4")
                    .any { (iface[it]?.toIntOrNull() ?: 0) < 12 }) return null
        }
        val version = when {
            v31 -> Version.V31
            listOf("s3", "s4").any { it in iface } ||
                listOf("h1", "h2", "h3", "h4")
                    .any { iface[it]?.contains('-') == true } -> Version.V2
            else -> Version.LEGACY
        }
        Profile(version, host, url.port, text, true)
    } catch (_: Exception) {
        // Never log user config, key or endpoint on malformed data.
        null
    }
    }

    private fun validBase64Key(value: String?): Boolean {
        if (value == null || !base64Alphabet.matches(value)) return false
        // 32-byte key = 43 base64 sextets followed by a single "=".
        // The final sextet must have its low two padding bits cleared.
        // This is pure JVM / Android 24 compatible (no java.util.Base64).
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val last = alphabet.indexOf(value[42])
        return last >= 0 && (last and 0x03) == 0
    }

    private fun validCidr(s: String): Boolean {
        val m = cidr.matchEntire(s) ?: return false
        val address = m.groupValues[1]
        val bits = m.groupValues[2].toIntOrNull() ?: return false
        return try {
            if (address.contains(':')) {
                bits in 0..128 && java.net.InetAddress.getByName(address).address.size == 16
            } else {
                bits in 0..32 && address.split('.').size == 4 &&
                    address.split('.').all {
                        it.isNotEmpty() && it.toIntOrNull() in 0..255
                    }
            }
        } catch (_: Exception) { false }
    }

    private fun validDnsLiteral(address: String): Boolean {
        if (address.isEmpty() || address.length > 45) return false
        return validCidr(if (address.contains(':')) "$address/128" else "$address/32")
    }

    private fun validUnsigned(raw: String?, upper: Int): Boolean =
        raw == null || (raw.matches(Regex("[0-9]{1,5}")) &&
            raw.toIntOrNull() in 0..upper)

    private fun validRange(raw: String?, upper: Long): Boolean {
        if (raw == null) return true
        val m = numeric.matchEntire(raw) ?: return false
        val low = m.groupValues[1].toLongOrNull() ?: return false
        val high = m.groupValues[2].ifEmpty { m.groupValues[1] }
            .toLongOrNull() ?: return false
        return low <= high && high <= upper
    }
}
