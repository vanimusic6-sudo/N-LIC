package io.github.romanvht.byedpi.subscription

/**
 * Same-node REALITY A/B research. No automatic configuration change and no
 * claim that an HTTPS failure identifies DPI. Fingerprint alone is not a
 * full browser's network behavior.
 */
internal object RealityComparison {
    const val ORIGINAL = "profile"
    const val FIREFOX = "firefox"
    const val CHROME = "chrome"
    const val QUICK_TRIALS = 6
    const val EXTENDED_TRIALS = 18

    // Mirrored blocks rotate the start profile across an extended run so
    // a changing network doesn't systematically favor the first candidate.
    val sequence = listOf(ORIGINAL, FIREFOX, CHROME, CHROME, FIREFOX, ORIGINAL)
    val extendedSequence = sequence +
        listOf(FIREFOX, CHROME, ORIGINAL, ORIGINAL, CHROME, FIREFOX) +
        listOf(CHROME, ORIGINAL, FIREFOX, FIREFOX, ORIGINAL, CHROME)

    fun schedule(trials: Int): List<String>? = when (trials) {
        QUICK_TRIALS -> sequence
        EXTENDED_TRIALS -> extendedSequence
        else -> null
    }

    data class Sample(
        val profile: String,
        val success: Boolean,
        val latencyMs: Long?,
        val socksConnectMs: Long? = null,
        val targetTlsMs: Long? = null,
        val httpResponseMs: Long? = null,
        val failureStage: String? = null,
        val secondEndpoint: Boolean = false,
    )

    data class Summary(
        val compact: String,
        val details: String,
    )

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 != 0) sorted[middle]
            else ((sorted[middle - 1].toDouble() + sorted[middle]) / 2).toLong()
    }

    fun report(samples: List<Sample>, plannedTrials: Int = QUICK_TRIALS): Summary {
        val expected = schedule(plannedTrials)
        val complete = expected != null && samples.map { it.profile } == expected
        val each = if (expected == null) 0 else plannedTrials / 3

        fun count(p: String) = samples.count { it.profile == p && it.success }
        val original = count(ORIGINAL)
        val firefox = count(FIREFOX)
        val chrome = count(CHROME)

        val best = maxOf(firefox, chrome)
        val verdict = when {
            !complete -> "Тест прерван. Итог нельзя сравнивать."
            original == each ->
                "Исходный REALITY доступен. Доказанного улучшения обхода нет."
            original == 0 && best == 0 ->
                "Все HTTPS-проверки неуспешны. Причина может быть не в DPI."
            best > original -> {
                val name = when {
                    firefox > chrome -> "Firefox"
                    chrome > firefox -> "Chrome"
                    else -> "Firefox и Chrome"
                }
                "Кандидат для повторной проверки: $name. Это не доказательство обхода DPI."
            }
            else -> "Альтернативные отпечатки не показали улучшения."
        }

        fun row(p: String, title: String): String {
            val matches = samples.filter { it.profile == p }
            val ok = matches.filter { it.success }
            val latency = median(ok.mapNotNull { it.latencyMs?.takeIf { it > 0L } })
            val timing = latency?.let { " · медиана ${it} мс" } ?: ""
            return "$title: ${ok.size}/${matches.size} HTTPS успешно$timing"
        }

        val compact = if (complete)
            "Проверено · оригинал $original/$each · Firefox $firefox/$each · Chrome $chrome/$each"
        else "Сравнение прервано · ${samples.size}/$plannedTrials"

        val successful = samples.filter { it.success }
        fun phase(name: String, select: (Sample) -> Long?): String {
            val value = median(successful.mapNotNull(select))
            return "$name: " + (value?.let { "${it} мс" } ?: "—")
        }

        val errorCounts = samples.filterNot { it.success }
            .mapNotNull { it.failureStage?.takeIf { stage ->
                stage.matches(Regex("[A-Z_]{1,24}"))
            } }.groupingBy { it }.eachCount()
        val errors = if (errorCounts.isEmpty()) ""
            else "\nСбои по этапам: " +
                errorCounts.entries.sortedBy { it.key }
                    .joinToString(", ") { "${it.key}: ${it.value}" }

        val detailed = buildString {
            append("REALITY · один сервер · ${samples.size}/$plannedTrials проверок\n\n")
            append(row(ORIGINAL, "Оригинал")).append('\n')
            append(row(FIREFOX, "Firefox")).append('\n')
            append(row(CHROME, "Chrome")).append("\n\n")
            append(verdict).append("\n\n")
            append("Этапы HTTPS · медианы успешных проверок\n")
            append(phase("SOCKS CONNECT", { it.socksConnectMs })).append('\n')
            append(phase("TLS проверочного сайта", { it.targetTlsMs })).append('\n')
            append(phase("Ответ HTTPS", { it.httpResponseMs }))
            append(errors)
            if (samples.any { it.secondEndpoint })
                append("\nРезервный тестовый сайт использован: ")
                    .append(samples.count { it.secondEndpoint }).append(" раз.")
            append("\n\nSOCKS CONNECT не измеряет REALITY-рукопожатие отдельно.")
            append("\nТест не меняет VPN и не доказывает причину фильтрации.")
        }

        return Summary(compact, detailed)
    }

    // Legacy interface kept for existing unit tests and call sites.
    fun summarize(samples: List<Sample>): String = report(samples).details
}
