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
    fun comparePaths(
        telegramDirect: ConnectionCheck,
        telegramSocks: ConnectionCheck,
        largeDirect: ConnectionCheck,
        largeSocks: ConnectionCheck,
    ): List<String> = buildList {
        if (!telegramDirect.ok && telegramSocks.ok) {
            add("N-LIC SOCKS установил соединение с проверяемым Telegram DC, " +
                "но прямое соединение не удалось. Это полезный признак различия путей.")
        } else if (!telegramDirect.ok && !telegramSocks.ok) {
            add("Прямой и локальный SOCKS-пути к проверяемому Telegram DC не открылись. " +
                "Для блокировки IP на уровне маршрута может потребоваться внешний ретранслятор.")
        }
        if (!largeDirect.ok && largeSocks.ok) {
            add("Через N-LIC успешно пришли 256 КиБ, а напрямую нет. " +
                "Выбранная стратегия может помогать на этом конкретном CDN.")
        } else if (!largeDirect.ok && !largeSocks.ok) {
            add("256 КиБ не скачались по обоим путям. Проверь ограничение потока, " +
                "доступность CDN, потери пакетов и изменение маршрута.")
        }
        if (largeDirect.ok && !largeSocks.ok) {
            add("Прямой большой ответ работает, SOCKS не справился: " +
                "возможно, выбранная DPI-цепочка вредит этому соединению.")
        }
    }

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
