package io.github.romanvht.byedpi.utility

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.data.StrategyResult
import java.io.File
import kotlin.math.sqrt

/**
 * Network-adaptive ByeDPI policies, selected at connection time.
 *
 * Per-carrier "best" flags cannot be truthfully hard-coded: TSPU rules vary
 * by ASN, location, date and protocol. Select only previously measured
 * successful results on this LINK TYPE. No SSID, SIM identity, IMSI, carrier
 * name or subscriber data are stored or sent to a strategy cloud.
 *
 * Tests are performed only when the user explicitly starts TestService.
 * A missing/stale result cannot be reported as a verified bypass.
 */
internal object AdaptiveDpiPolicy {
    const val KEY = "nlic_adaptive_bydpi"
    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private val defaults = mapOf(
        AccessType.WIFI to "-s0+sm",
        AccessType.CELLULAR to "-s0+sm",
        AccessType.ETHERNET to "-s0+sm",
        AccessType.OTHER to "-s0+sm",
    )

    data class Choice(val command: String, val measured: Boolean, val type: AccessType)

    fun choose(
        type: AccessType,
        results: List<StrategyResult>,
        fallback: String? = null,
    ): Choice {
        val credible = results.filter {
            it.isCompleted &&
                it.networkType == type.name &&
                it.successCount >= 3 && it.totalRequests >= 3 &&
                it.successPercentage >= 70 &&
                isSafeCommand(it.command)
        }
        // Wilson's lower confidence bound values repeated success over
        // lucky one-off 100% samples. A genuinely successful large download
        // also wins over tiny response-only probes at comparable confidence.
        // Final tie-breaker prefers cheaper packet transformations to
        // reduce battery, heat and network retries.
        val best = credible.sortedWith(
            compareByDescending<StrategyResult> { confidenceScore(it) }
                .thenBy { StrategyRanking.estimatedPacketCost(it.command) }
                .thenBy { StrategyRanking.medianSuccessLatencyMs(it) ?: Long.MAX_VALUE }
        ).firstOrNull()
        if (best != null) return Choice(best.command, true, type)
        val conservative = defaults[type] ?: defaults.getValue(AccessType.WIFI)
        return Choice(conservative.ifEmpty { fallback.orEmpty() }, false, type)
    }

    fun decide(context: Context, enabled: Boolean, fallback: String): Choice? {
        if (!enabled) return null
        val access = NetworkEnvironment.capture(context).type
        val file = File(context.filesDir, AccessPolicy.resultsFileName(access))
        val candidates = if (file.exists() && file.length() in 1..MAX_BYTES &&
            System.currentTimeMillis() - file.lastModified() in 0..MAX_AGE_MS) {
            runCatching {
                val json = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<StrategyResult>>() {}.type
                Gson().fromJson<List<StrategyResult>>(json, type).orEmpty().take(256)
            }.getOrDefault(emptyList())
        } else emptyList()
        return choose(access, candidates, fallback)
    }

    /** 95% Wilson interval lower bound for observed bypass successes. */
    internal fun confidenceScore(result: StrategyResult): Double {
        if (result.totalRequests < 1 ||
            result.successCount !in 0..result.totalRequests) return 0.0
        val n = result.totalRequests.toDouble()
        val p = result.successCount / n
        val z2 = 3.8416
        val score = (p + z2 / (2 * n) -
            1.96 * sqrt((p * (1 - p) + z2 / (4 * n)) / n)) / (1 + z2 / n)
        return (score - if (result.streamProbe?.outcome in
                setOf("STALLED_AFTER_DATA", "PREMATURE_EOF")) 0.20 else 0.0)
            .coerceAtLeast(0.0)
    }

    /** Never allow benchmark cache commands to change bind address, port,
     * local file handling or process lifecycle. Strategy options only.
     */
    internal fun isSafeCommand(value: String): Boolean {
        if (value.length !in 2..2048 || !value.startsWith('-') ||
            value.none { it.code !in 32..126 }.not()) return false
        if (value.any { it in "\r\n\u0000" }) return false
        val tokens = shellSplit(value)
        if (tokens.isEmpty()) return false
        val forbidden = listOf("--cache-file", "--hosts", "--ipset",
            "--exec", "--log-file", "--ip", "--port", "--po", "--por",
            "--daemon", "--transparent", "-i", "-p", "-y", "-H", "-j")
        return tokens.none { token ->
            forbidden.any { prefix ->
                token == prefix || token.startsWith("$prefix=") ||
                    (prefix.startsWith("-") && !prefix.startsWith("--") &&
                        token.startsWith(prefix) && token.length > prefix.length)
            }
        }
    }
}
