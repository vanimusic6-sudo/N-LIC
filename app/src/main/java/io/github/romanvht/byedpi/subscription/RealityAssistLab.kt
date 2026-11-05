package io.github.romanvht.byedpi.subscription

/**
 * A single explicit REALITY comparison. First examine each distinct uTLS
 * ClientHello with stock TCP, then each available TCP technique on the
 * fastest verified uTLS profile. This is NOT the full Cartesian product.
 *
 * A row requires two real certificate-verified HTTPS requests AND one
 * complete 512-KiB encrypted download to be "working". Never treat a
 * compiler-produced JSON or an opened SOCKS socket as a passed test.
 */
internal object RealityAssistLab {
    const val MODE_ALL = 40
    const val PROFILE = "profile"
    data class Row(
        val fingerprint: String,
        val trick: String,
        val https1: Long?,
        val https2: Long?,
        val transferOk: Boolean,
        val error: String = "",
    ) {
        val successes get() = listOf(https1, https2).count { it != null && it > 0 }
        val fullSuccess get() = successes == 2 && transferOk
        val medianMs: Long? get() {
            val n = listOfNotNull(https1, https2).sorted()
            return if (n.isEmpty()) null else if (n.size == 1) n[0]
            else ((n[0].toDouble() + n[1]) / 2.0).toLong()
        }

        val id get() = "$fingerprint|$trick"
        fun title(): String {
            val fp = when (fingerprint) {
                "chrome" -> "Chrome"
                "firefox" -> "Firefox"
                "safari" -> "Safari"
                else -> "Из подписки"
            }
            return if (trick == RealityTcpTricks.STOCK) "$fp · TCP обычный"
                else "$fp · ${RealityTcpTricks.label(trick)}"
        }

        fun human(): String {
            val state = when {
                fullSuccess -> "✓ Работает"
                successes == 0 -> "✕ Не подключился"
                !transferOk -> "⚠ HTTPS есть, поток не прошёл"
                else -> "⚠ Нестабильно"
            }
            val timing = medianMs?.let { " · ${it} мс" } ?: ""
            val why = if (!fullSuccess && error.isNotBlank()) " · $error" else ""
            return "$state · ${title()} · HTTPS $successes/2$timing$why"
        }
        fun encode(): String = listOf(
            fingerprint, trick, (https1 ?: 0).toString(),
            (https2 ?: 0).toString(), if (transferOk) "1" else "0",
            error.takeIf { it.matches(Regex("[A-Z_]{1,28}")) } ?: ""
        ).joinToString("|")
    }

    fun parse(raw: String): Row? {
        val p = raw.split('|')
        if (p.size != 6) return null
        if (p[0] !in listOf(PROFILE) + RealityAssistPolicy.browserCandidates ||
            p[1] !in RealityTcpTricks.variants) return null
        val a = p[2].toLongOrNull()?.takeIf { it in 0..30_000 } ?: return null
        val b = p[3].toLongOrNull()?.takeIf { it in 0..30_000 } ?: return null
        if (p[4] !in listOf("0","1") ||
            !p[5].matches(Regex("[A-Z_]{0,28}"))) return null
        return Row(p[0], p[1], a.takeIf { it > 0 },
            b.takeIf { it > 0 }, p[4] == "1", p[5])
    }

    /** Verified end-to-end availability first; then lower median among
     * equally successful rows. NO hidden +90ms / +15% baseline threshold.
     * Ties prefer stock rather than adding a needless transport trick.
     */
    fun best(rows: List<Row>): Row? = rows.filter { it.fullSuccess }
        .minWithOrNull(compareBy<Row> { it.medianMs ?: Long.MAX_VALUE }
            .thenBy { if (it.trick == RealityTcpTricks.STOCK) 0 else 1 }
            .thenBy { it.id })

    fun bestBrowser(rows: List<Row>): String = best(rows)?.fingerprint ?: PROFILE

    fun candidates(baselineFp: String): List<String> =
        (listOf(PROFILE) + RealityAssistPolicy.browserCandidates)
            .distinctBy { if (it == PROFILE) baselineFp else it }

    fun compact(rows: List<Row>): String {
        val verified = rows.count { it.fullSuccess }
        val champion = best(rows)
        return if (champion == null)
            "Проверено ${rows.size} способов · рабочих не найдено"
        else "Работает $verified/${rows.size} · лучший: ${champion.title()} · " +
            "${champion.medianMs} мс"
    }

    fun explanation(rows: List<Row>): String {
        val champion = best(rows)
        val second = rows.filter { it.fullSuccess && it != champion }
            .minByOrNull { it.medianMs ?: Long.MAX_VALUE }
        val difference = if (champion != null && second != null)
            second.medianMs!! - champion.medianMs!! else null
        return buildString {
            append("Один REALITY-сервер · без изменения ключей.\n")
            append("Сначала проверены браузерные отпечатки на обычном TCP, ")
            append("затем способы TCP на лучшем подтверждённом отпечатке.\n\n")
            append("Каждый способ: два HTTPS + полная передача 512 КиБ.\n")
            append("Работает: ${rows.count { it.fullSuccess }}/${rows.size}.\n")
            if (champion != null) {
                append("Лучший по медиане HTTPS: ${champion.title()} · ")
                append("${champion.medianMs} мс.\n")
                if (difference != null && difference < 50L)
                    append("Разница с ближайшим вариантом небольшая: ")
                        .append("${difference} мс. Выбор может меняться от шума сети.\n")
                append("Выбери подходящий рабочий вариант и нажми «Использовать».\n")
            } else {
                append("Подтверждённых вариантов нет. Сохранять нечего.\n")
            }
            append("Этот тест показывает доступность, но не доказывает обход DPI.")
        }
    }
}
