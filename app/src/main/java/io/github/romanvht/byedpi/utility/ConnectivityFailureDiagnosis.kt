package io.github.romanvht.byedpi.utility

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Safe-to-share categories. Never expose raw exception messages or user network identifiers. */
internal object ConnectivityFailureDiagnosis {
    fun classify(error: Throwable): String {
        if (error is SecurityException) return "APP_NETWORK_PERMISSION_DENIED"
        if (error is UnknownHostException) return "DNS_FAILURE"
        if (error is SocketTimeoutException) return "TIMEOUT"
        if (error is SSLException) return "TLS_FAILURE"

        // SocketException / GaiException often wrap ErrnoException. Android
        // OEMs use different wording; walk the cause chain without publishing
        // exceptions, destination IPs, or device-specific kernel messages.
        val details = generateSequence(error) { it.cause }
            .take(6)
            .joinToString(" ") {
                it::class.java.simpleName + " " + it.message.orEmpty()
            }.lowercase()
        return when {
            listOf("eacces", "eperm", "permission denied", "operation not permitted")
                .any(details::contains) -> "SOCKET_ACCESS_DENIED"
            listOf("enetunreach", "enetworkunreachable", "network is unreachable",
                "network unreachable", "enetdown", "network is down", "no route to host",
                "ehostunreach", "host is unreachable").any(details::contains) -> "NO_NETWORK_ROUTE"
            "econnrefused" in details || "connection refused" in details -> "TCP_REFUSED"
            "econnreset" in details || "connection reset" in details -> "TCP_RESET"
            error is IOException -> "NETWORK_IO_FAILURE"
            else -> "NETWORK_FAILURE"
        }
    }

    /**
     * When every independent public endpoint fails almost instantly, testing
     * more DPI desync strings is premature; first identify phone / per-app
     * restrictions, VPN lockdown, airplane mode and mobile-data status.
     */
    fun describe(
        checks: List<ConnectionCheck>,
        physicalNetworkVisible: Boolean,
        airplaneModeEnabled: Boolean?,
        internetPermissionGranted: Boolean,
        networkStatePermissionGranted: Boolean,
    ): List<String> = buildList {
        if (!internetPermissionGranted || !networkStatePermissionGranted) {
            add("КРИТИЧНО: N-LIC не имеет необходимых разрешений INTERNET/ACCESS_NETWORK_STATE. " +
                "Проверь установленный APK и настройки приложения.")
        }
        if (airplaneModeEnabled == true) {
            add("На телефоне включён режим полёта. Проверь, активна ли передача данных.")
        }
        val controlChecks = checks.filter {
            it.target.startsWith("Control IPv4") || it.target.startsWith("Control IPv6")
        }
        if (controlChecks.isNotEmpty() && controlChecks.all { !it.ok } &&
            controlChecks.any { it.detail == "SOCKET_ACCESS_DENIED" || it.detail == "NO_NETWORK_ROUTE" }) {
            add("Контрольные TCP-соединения по числовым IP тоже недоступны. " +
                "Это не объясняется одной лишь блокировкой DNS или конкретного Telegram DC.")
        }
        if (checks.size >= 6 && checks.none { it.ok } &&
            checks.count { it.latencyMs < 250 } >= 5) {
            add("Большинство независимых соединений отказали немедленно. " +
                "В первую очередь проверь системный интернет для N-LIC: разрешение мобильных " +
                "данных для приложения, активную SIM, режим полёта и VPN-режим " +
                "«Блокировать подключения без VPN». Это НЕ доказательство DPI-блокировки.")
        }
        if (!physicalNetworkVisible && checks.none { it.ok }) {
            add("Android не показывает N-LIC физическую сеть, и ни один запрос не завершился. " +
                "Проверь, открываются ли обычные сайты в браузере на той же мобильной сети " +
                "при выключенном N-LIC. Не меняй DPI-стратегии, пока этот тест не пройден.")
        }
    }
}
