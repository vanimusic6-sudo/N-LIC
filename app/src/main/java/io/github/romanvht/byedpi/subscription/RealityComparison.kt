package io.github.romanvht.byedpi.subscription

/**
 * A/B measurement, not an automatic VPN configuration or a DPI verdict.
 *
 * Each sample means an authenticated SOCKS -> Xray -> REALITY -> HTTPS GET,
 * with the same selected VPN node and one user-selected uTLS ClientHello.
 * Order is mirrored to reduce time-order bias. Never overwrite the provider
 * profile or select a different server as a consequence of this measurement.
 */
internal object RealityComparison {
    const val ORIGINAL = "profile"
    const val FIREFOX = "firefox"
    const val CHROME = "chrome"

    val sequence: List<String> =
        listOf(ORIGINAL, FIREFOX, CHROME, CHROME, FIREFOX, ORIGINAL)

    data class Sample(val profile: String, val success: Boolean, val latencyMs: Long?)

    fun summarize(samples: List<Sample>): String {
        fun line(profile: String, name: String): String {
            val subset = samples.filter { it.profile == profile }
            val ok = subset.filter { it.success }
            val latency = ok.mapNotNull { it.latencyMs?.takeIf { ms -> ms > 0L } }
            val speed = if (latency.isEmpty()) "" else
                " · среднее ${latency.average().toLong()} мс"
            return "$name: ${ok.size}/${subset.size} HTTPS успешно$speed"
        }
        val original = samples.count { it.profile == ORIGINAL && it.success }
        val firefox = samples.count { it.profile == FIREFOX && it.success }
        val chrome = samples.count { it.profile == CHROME && it.success }
        val maxAlt = maxOf(firefox, chrome)
        val verdict = when {
            samples.size != sequence.size ->
                "Тест прерван. Итог нельзя сравнивать."
            original == 2 ->
                "Исходный REALITY доступен. Доказанного улучшения обхода нет."
            original == 0 && maxAlt == 0 ->
                "Ни один вариант не подтвердил HTTPS. Причина может быть не в DPI."
            maxAlt > original -> {
                val best = if (firefox > chrome) "Firefox"
                    else if (chrome > firefox) "Chrome" else "Firefox / Chrome"
                "Кандидат для повторной проверки: $best. " +
                    "Это не доказательство обхода DPI."
            }
            else -> "Альтернативные отпечатки не показали улучшения."
        }
        return listOf(
            "Сравнение на одном сервере · по 2 подключения",
            line(ORIGINAL, "Оригинал"),
            line(FIREFOX, "Firefox"),
            line(CHROME, "Chrome"),
            verdict,
            "Тест не меняет сервер и настройки VPN. " +
                "Измерения небольшие; для вывода повтори на той же сети."
        ).joinToString("\n")
    }
}
