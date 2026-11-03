package io.github.romanvht.byedpi.utility

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.data.StrategyResult
import java.io.File

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
        val best = credible.sortedWith(StrategyRanking.comparator).firstOrNull()
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

    private fun isSafeCommand(value: String): Boolean =
        value.length in 2..2048 && value.startsWith('-') &&
            value.none { it in "\r\n\u0000" } &&
            // Do not restore arbitrary external file names/remote scripts
            // from a stale or tampered benchmark JSON.
            listOf("--cache-file", "--hosts", "--ipset", "--exec", "--log-file")
                .none { value.contains(it) } &&
            value.all { it.code in 32..126 }
}
