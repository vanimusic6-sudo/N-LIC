package io.github.romanvht.byedpi.utility

/**
 * Network-level symptoms are only evidence: a blocked IP, TLS failure and
 * congested data centre can all produce a TCP timeout.
 */
internal data class ConnectionCheck(
    val target: String,
    val ok: Boolean,
    val latencyMs: Long,
    val detail: String,
    val bytes: Int = 0,
)

internal object NetworkHealthInterpretation {
    fun describe(
        youtube: ConnectionCheck,
        telegram: ConnectionCheck,
        smallBody: ConnectionCheck,
        largeBody: ConnectionCheck,
    ): List<String> = buildList {
        if (youtube.ok && !telegram.ok) {
            add("Telegram DC: соединение по IP не установлено, тогда как YouTube TCP доступен. " +
                "Это совместимо с фильтрацией IP/маршрута, но само по себе её не доказывает. " +
                "TLS-разделение не исправит заблокированный IP.")
        }
        if (smallBody.ok && !largeBody.ok) {
            add("Малый HTTP-ответ пришёл, большой не завершился. " +
                "Возможны обрыв потока, ограничение скорости, недоступность CDN или фильтрация тела.")
        }
        if (smallBody.ok && largeBody.ok && largeBody.latencyMs > smallBody.latencyMs * 12) {
            add("Большой ответ заметно медленнее малого; проверь пропускную способность и потери.")
        }
        if (!youtube.ok && !telegram.ok) {
            add("Несколько независимых TCP-целей недоступны: проверь активную сеть, DNS и маршрутизацию.")
        }
        if (isEmpty()) {
            add("Тестовые соединения установлены либо данных недостаточно. " +
                "Работа самого приложения YouTube/Telegram по QUIC и MTProto не проверялась.")
        }
    }
}
