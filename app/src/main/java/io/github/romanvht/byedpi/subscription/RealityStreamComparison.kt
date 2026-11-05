package io.github.romanvht.byedpi.subscription

/** Human-readable 3×512-KiB test of HTTPS delivery over the same REALITY node. */
internal object RealityStreamComparison {
    const val TRIALS = 3
    val sequence = listOf("profile", "firefox", "chrome")

    internal data class Sample(
        val fingerprint: String,
        val value: RealityStreamProbe.Result,
    )

    internal data class Summary(val compact: String, val details: String)

    fun summarize(samples: List<Sample>): Summary {
        val complete = samples.map { it.fingerprint } == sequence
        val ok = samples.count { it.value.successful }
        val compact = if (complete)
            "Поток HTTPS · $ok/3 проверок по 512 КБ"
        else "Поток HTTPS · тест прерван (${samples.size}/3)"

        fun label(fingerprint: String): String = when (fingerprint) {
            "firefox" -> "Firefox"
            "chrome" -> "Chrome"
            else -> "Оригинал"
        }

        val details = buildString {
            append("Передача REALITY · 3 варианта × 512 КиБ\n\n")
            for (sample in samples) {
                append(label(sample.fingerprint)).append(": ")
                val result = sample.value
                if (result.successful) {
                    append("512 КиБ доставлены")
                    result.mbps?.let {
                        append(" · ")
                        append(String.format(java.util.Locale.US, "%.2f", it))
                        append(" Мбит/с")
                    }
                    result.totalMs?.let { append(" · всего ").append(it).append(" мс") }
                } else {
                    append("неполная передача (")
                    append(result.receivedBytes / 1024)
                    append(" КиБ)")
                    append(" · этап ").append(result.failure ?: "UNKNOWN")
                }
                append('\n')
            }
            append("\n")
            append(if (complete && ok == 3)
                "Все варианты передали контрольный объём через защищённый HTTPS."
                else if (complete) "Некоторые передачи не завершились. Это не доказывает блокировку DPI."
                else "Тест не завершён; сравнение недостоверно.")
            append("\n\nПроверяется небольшой HTTPS-поток, а не непрерывное видео.")
            append("\nЭто не измерение точного рукопожатия REALITY.")
            append("\nСервер и настройки VPN не изменяются.")
        }
        return Summary(compact, details)
    }
}
