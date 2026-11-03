package io.github.romanvht.byedpi.subscription

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Manual HTTPS subscription importer. Links frequently contain tokens:
 * avoid redirects, HTTP, outbound logs, and error messages containing URLs.
 */
internal object SubscriptionFetcher {
    private const val MAX_BYTES = 512 * 1024

    fun validSubscriptionUrl(input: String): Boolean {
        if (input.length !in 12..2048 || input.any { it.isWhitespace() }) return false
        return try {
            val url = URI(input)
            val host = url.host?.lowercase() ?: return false
            url.scheme.equals("https", true) && url.userInfo == null &&
                url.fragment == null && url.port in -1..65535 &&
                url.port != 0 && host != "localhost" &&
                !host.endsWith(".local") && !host.endsWith(".internal") &&
                !host.endsWith(".localhost") &&
                !host.startsWith("127.") && !host.startsWith("10.") &&
                !host.startsWith("192.168.") &&
                !host.startsWith("169.254.")
        } catch (_: Exception) { false }
    }

    private fun publicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isSiteLocalAddress && !address.isLinkLocalAddress &&
        !address.isMulticastAddress

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        require(validSubscriptionUrl(url)) { "Use a public HTTPS subscription URL" }
        val uri = URI(url)
        // Reject DNS resolutions to private/link-local destinations for
        // user-pasted URLs. This is a defence in depth, not a DNS-rebinding
        // guarantee; automatic refresh is intentionally disabled.
        val addresses = InetAddress.getAllByName(uri.host)
        require(addresses.isNotEmpty() && addresses.all(::publicAddress)) {
            "Subscription hostname is not publicly routable"
        }

        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 5000
        conn.readTimeout = 9000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "text/plain,application/octet-stream;q=0.8")
        try {
            val status = conn.responseCode
            require(status == 200) { "Subscription server returned HTTP $status" }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (out.size() + n > MAX_BYTES) throw IllegalArgumentException(
                        "Subscription exceeds 512 KiB"
                    )
                    out.write(buffer, 0, n)
                }
            }
            out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }
}
