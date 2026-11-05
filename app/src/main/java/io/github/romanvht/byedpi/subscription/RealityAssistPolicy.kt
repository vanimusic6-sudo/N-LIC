package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser

/**
 * LIC-Assist for one explicitly selected VLESS + REALITY + TCP (RAW) node.
 *
 * No server/protocol switch, no custom encryption, no synthetic TLS records.
 * A failed validated egress check may retry the *same* node with the other
 * Xray-supported uTLS browser ClientHello. The runtime verifies actual HTTPS
 * over the authenticated tunnel before it exposes Android VPN routes.
 */
internal object RealityAssistPolicy {
    const val SMART = "smart"
    const val PROFILE = "profile"
    const val FIREFOX = "firefox"
    const val CHROME = "chrome"

    /**
     * Null means "use exact fingerprint from imported profile".
     *
     * The additional candidate is bounded to one, even if the original
     * fingerprint is not in our experimental set. Never attempt an alternate
     * for a non-REALITY node or while LIC-Auto is choosing VPN servers.
     */
    fun candidates(
        mode: String,
        compiledBaseline: XrayConfigCompiler.Result.Compiled,
        serverAutoEnabled: Boolean,
    ): List<String?> {
        if (mode != SMART || serverAutoEnabled) {
            return listOf(explicitFingerprint(mode))
        }
        val current = baselineFingerprint(compiledBaseline.json) ?: return listOf(null)
        val alternate = if (current == FIREFOX) CHROME else FIREFOX
        return listOf(null, alternate)
    }

    fun explicitFingerprint(mode: String): String? = when (mode) {
        FIREFOX -> FIREFOX
        CHROME -> CHROME
        else -> null
    }

    internal fun baselineFingerprint(compiled: String): String? = runCatching {
        val outbound = JsonParser.parseString(compiled).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject
        if (outbound.get("protocol")?.asString != "vless") return null
        val stream = outbound.getAsJsonObject("streamSettings") ?: return null
        if (stream.get("network")?.asString != "raw" ||
            stream.get("security")?.asString != "reality") return null
        val fp = stream.getAsJsonObject("realitySettings")?.get("fingerprint")
            ?.asString?.lowercase() ?: return null
        fp
    }.getOrNull()
}
