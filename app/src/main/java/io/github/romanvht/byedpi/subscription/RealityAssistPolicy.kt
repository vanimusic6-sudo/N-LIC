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
    private const val MIN_ADVANTAGE_MS = 90L
    private const val MIN_ADVANTAGE_PCT = 15

    data class Measurement(
        val fingerprint: String?, // null -> exact imported profile
        val httpsMs: Long?, // certificate-verified Xray HTTPS, not TCP connect
    )

    /** Only meaningful for a manually selected REALITY TCP+RAW node. */
    fun calibrationCandidates(baselineFingerprint: String): List<String?> =
        (listOf<String?>(null) + browserCandidates)
            .distinctBy { it ?: baselineFingerprint }

    /**
     * Select an alternate only when it authenticates successfully and beats
     * a working original by >=90ms AND >=15%, to avoid choosing a transient
     * lucky jitter sample. If baseline fails, a verified browser variant
     * is preferable to a broken one. Scores are not censorship verdicts.
     */
    fun chooseBest(
        baselineFingerprint: String,
        samples: List<Measurement>,
    ): String? {
        val valid = samples.filter { it.httpsMs != null && it.httpsMs in 1L..30_000L }
            .distinctBy { it.fingerprint ?: baselineFingerprint }
        val best = valid.minByOrNull { it.httpsMs!! } ?: return null
        val baseline = valid.firstOrNull {
            (it.fingerprint ?: baselineFingerprint) == baselineFingerprint
        }
        if (baseline == null) return best.fingerprint
        if (best.fingerprint == null ||
            (best.fingerprint ?: baselineFingerprint) == baselineFingerprint) return null
        val old = baseline.httpsMs!!
        val new = best.httpsMs!!
        return if (old - new >= MIN_ADVANTAGE_MS &&
            (old - new) * 100 >= old * MIN_ADVANTAGE_PCT) best.fingerprint
        else null
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
