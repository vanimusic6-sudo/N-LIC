package io.github.romanvht.byedpi.subscription

/**
 * A clear and reproducible list of *real* Xray client-side profiles.
 * The same REALITY server, SNI, UUID, public key, shortId and Vision flow
 * must be used in every trial. Each candidate runs twice, in mirrored order.
 */
internal object RealityWorkbench {
    data class Profile(val fingerprint: String?, val trick: String) {
        val id: String get() = "${fingerprint ?: "profile"}~$trick"
        val label: String get() = when (fingerprint) {
            "chrome" -> "Chrome"
            "firefox" -> "Firefox"
            "safari" -> "Safari"
            else -> "Из подписки"
        } + if (trick == RealityTcpTricks.STOCK) " · обычный TCP"
            else " · ${RealityTcpTricks.label(trick)}"
    }
    data class Sample(val id: String, val ms: Long?, val error: String?)
    data class Entry(
        val profile: Profile,
        val passed: Int,
        val tested: Int,
        val medianMs: Long?,
        val failures: String,
    ) {
        val good get() = passed == 2 && tested == 2
        val status: String get() = when {
            tested == 0 -> "Не проверено"
            good -> "Работает"
            passed > 0 -> "Нестабильно"
            else -> "Не работает"
        }
    }
    data class Report(val entries: List<Entry>, val best: Entry?, val complete: Boolean) {
        val compact get() = if (complete)
            "LIC-Assist · ${entries.count { it.good }} из ${entries.size} работают" +
                (best?.let { " · лучший: ${it.profile.label}" } ?: "")
        else "Проверка прервана · результаты неполные"
        val details get() = buildString {
            append("Все варианты на одном REALITY-сервере\n\n")
            entries.forEach { e ->
                append(e.status).append("  ·  ").append(e.profile.label)
                append("\n")
                append("${e.passed}/${e.tested} HTTPS")
                e.medianMs?.let { append(" · медиана ${it} мс") }
                if (e.failures.isNotEmpty()) append(" · этап ${e.failures}")
                append("\n\n")
            }
            append(if (complete && best != null)
                "По этим замерам самый быстрый устойчивый: ${best.profile.label}."
            else "Нельзя автоматически выбрать победителя: нет полного устойчивого результата.")
            append("\nНебольшие отличия времени могут быть сетевым шумом.")
            append("\nУспех HTTPS не доказывает обход DPI.")
        }
    }

    /** Unique stock-uTLS variants plus each transport trick on baseline fp. */
    fun profiles(baselineFingerprint: String): List<Profile> =
        (listOf(Profile(null, RealityTcpTricks.STOCK)) +
            RealityAssistPolicy.browserCandidates
                .filter { it != baselineFingerprint }
                .map { Profile(it, RealityTcpTricks.STOCK) } +
            RealityTcpTricks.available(false)
                .filter { it != RealityTcpTricks.STOCK }
                .map { Profile(null, it) })
            .distinctBy { (it.fingerprint ?: baselineFingerprint) to it.trick }

    fun schedule(profiles: List<Profile>): List<Profile> = profiles + profiles.asReversed()

    fun evaluate(profiles: List<Profile>, samples: List<Sample>): Report {
        val entries = profiles.map { p ->
            val subset = samples.filter { it.id == p.id }
            val valid = subset.mapNotNull { it.ms?.takeIf { ms -> ms in 1L..30_000L } }
            val sorted = valid.sorted()
            val median = when (sorted.size) {
                0 -> null
                1 -> sorted[0]
                else -> (sorted[0] + sorted[1]) / 2L
            }
            Entry(p, valid.size, subset.size, median,
                subset.filter { it.ms == null }.mapNotNull { it.error }
                    .distinct().take(2).joinToString(", "))
        }
        val complete = samples.size == profiles.size * 2 &&
            schedule(profiles).map { it.id } == samples.map { it.id }
        // Reliability beats latency. All must pass 2/2; when multiple pass,
        // select actual lowest median with no arbitrary 90ms/15% threshold.
        val best = if (complete)
            entries.filter { it.good }.minWithOrNull(
                compareBy<Entry> { it.medianMs ?: Long.MAX_VALUE }
                    .thenBy { profiles.indexOf(it.profile) }
            ) else null
        return Report(entries, best, complete)
    }

    fun encode(entries: List<Entry>): String =
        entries.joinToString(";") { e ->
            "${e.profile.id},${e.passed},${e.tested},${e.medianMs ?: 0L}"
        }

    fun decode(raw: String, profiles: List<Profile>): List<Entry>? {
        val fields = raw.split(';')
        if (fields.size != profiles.size) return null
        return fields.mapIndexed { i, item ->
            val t = item.split(',')
            if (t.size != 4 || t[0] != profiles[i].id) return null
            val pass = t[1].toIntOrNull() ?: return null
            val tested = t[2].toIntOrNull() ?: return null
            val ms = t[3].toLongOrNull() ?: return null
            if (pass !in 0..2 || tested !in 0..2 || pass > tested ||
                ms !in 0L..30_000L) return null
            Entry(profiles[i], pass, tested, ms.takeIf { it > 0L }, "")
        }
    }
}
