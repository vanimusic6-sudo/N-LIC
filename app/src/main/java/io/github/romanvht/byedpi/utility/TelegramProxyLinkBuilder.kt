package io.github.romanvht.byedpi.utility

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Telegram MTProxy needs a REMOTE relay. N-LIC's local ByeDPI cannot change a
 * destination IP that the provider blocks. This helper transfers user-provided
 * remote proxy parameters to Telegram, without saving or logging the secret.
 */
internal object TelegramProxyLinkBuilder {
    fun build(server: String, port: Int, secret: String): String {
        val cleanedHost = server.trim()
        val cleanedSecret = secret.trim()
        require(cleanedHost.matches(Regex("[A-Za-z0-9.-]{1,253}")) &&
            !cleanedHost.startsWith(".") && !cleanedHost.endsWith(".")) {
            "Enter a valid domain or IPv4 address"
        }
        require(port in 1..65535) { "Invalid MTProxy port" }
        require(cleanedSecret.matches(Regex("(?i)[0-9a-f]{32,512}")) &&
            cleanedSecret.length % 2 == 0) { "Invalid hex MTProxy secret" }
        val encodedHost = URLEncoder.encode(cleanedHost, StandardCharsets.UTF_8.name())
        return "tg://proxy?server=$encodedHost&port=$port&secret=$cleanedSecret"
    }
}
