package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser

/**
 * Client-only REALITY transport selection. The same VLESS TCP/RAW node and
 * keys are used for all candidates; never alter SNI, Vision flow or REALITY
 * authentication. Xray uTLS modifies only the ClientHello, not all browser
 * behavior. Compatibility-first: old Edge/iOS/QQ profiles may fail on recent
 * REALITY servers, so they are intentionally excluded from Smart.
 */
internal object RealityAssistPolicy {
    const val SMART = "smart"
    const val PROFILE = "profile"
    const val FIREFOX = "firefox"
    const val CHROME = "chrome"
    const val SAFARI = "safari"

    // In verified modern Xray this is a *curated* set, not every uTLS name.
    val browserCandidates = listOf(CHROME, FIREFOX, SAFARI)
    data class Measurement(
        val fingerprint: String?, // null -> exact imported profile
        val httpsMs: Long?, // certificate-verified Xray HTTPS, not TCP connect
    )

    /** Only meaningful for a manually selected REALITY TCP+RAW node. */
    fun calibrationCandidates(baselineFingerprint: String): List<String?> =
        (listOf<String?>(null) + browserCandidates)
            .distinctBy { it ?: baselineFingerprint }

    /**
     * Legacy one-off candidate ranking retained for compatibility only.
     * Full lab uses two HTTPS checks + 512KiB and selects the lowest median
     * among fully verified candidates. No arbitrary threshold that forces
     * the original to win despite a measured faster Chrome/Firefox.
     */
    fun chooseBest(
        baselineFingerprint: String,
        samples: List<Measurement>,
    ): String? {
        val valid = samples.filter {
            it.httpsMs != null && it.httpsMs in 1L..30_000L
        }.distinctBy { it.fingerprint ?: baselineFingerprint }
        val candidate = valid.minWithOrNull(
            compareBy<Measurement> { it.httpsMs ?: Long.MAX_VALUE }
                .thenBy { if (it.fingerprint == null) 0 else 1 }
        ) ?: return null
        return candidate.fingerprint?.takeUnless { it == baselineFingerprint }
    }

    /**
     * Cached confirmed winner first. If it fails, try original and other
     * compatible uTLS profiles on the *same* node. Never generate random
     * fingerprints or disable TLS/REALITY.
     */
    fun candidates(
        mode: String,
        compiledBaseline: XrayConfigCompiler.Result.Compiled,
        serverAutoEnabled: Boolean,
        preferred: String? = null,
    ): List<String?> {
        if (mode != SMART || serverAutoEnabled) {
            return listOf(explicitFingerprint(mode))
        }
        val current = baselineFingerprint(compiledBaseline.json) ?: return listOf(null)
        val safe = preferred?.takeIf { it in browserCandidates || it == PROFILE }
        val first: String? = if (safe == PROFILE) null else safe
        return (listOf(first, null) + browserCandidates)
            .distinctBy { it ?: current }
            .take(4)
    }

    fun explicitFingerprint(mode: String): String? = when (mode) {
        FIREFOX -> FIREFOX
        CHROME -> CHROME
        SAFARI -> SAFARI
        else -> null
    }

    internal fun baselineFingerprint(compiled: String): String? = runCatching {
        val outbound = JsonParser.parseString(compiled).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject
        if (outbound.get("protocol")?.asString != "vless") return@runCatching null
        val stream = outbound.getAsJsonObject("streamSettings") ?: return@runCatching null
        if (stream.get("network")?.asString != "raw" ||
            stream.get("security")?.asString != "reality") return@runCatching null
        val fp = stream.getAsJsonObject("realitySettings")?.get("fingerprint")
            ?.asString?.lowercase() ?: return@runCatching null
        fp
    }.getOrNull()
}
