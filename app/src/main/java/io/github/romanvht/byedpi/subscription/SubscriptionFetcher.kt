package io.github.romanvht.byedpi.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Explicit HTTPS-only subscription import. The DNS answers passed through
 * Dns.lookup are EXACTLY those OkHttp uses to establish the TCP connection.
 *
 * Unlike a preflight InetAddress.getAllByName followed by URL.openConnection(),
 * this does not have a DNS-rebinding time-of-check/time-of-use gap.
 *
 * Subscription links can contain access tokens: never log URLs, bodies,
 * response headers or provider exceptions.
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

    internal fun publicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isSiteLocalAddress && !address.isLinkLocalAddress &&
        !address.isMulticastAddress

    internal fun validateDnsAnswers(addresses: List<InetAddress>): List<InetAddress> {
        require(addresses.isNotEmpty() && addresses.all(::publicAddress)) {
            "Subscription must resolve to a public address"
        }
        return addresses
    }

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        require(validSubscriptionUrl(url)) { "Use a public HTTPS subscription URL" }
        val host = URI(url).host ?: error("Invalid HTTPS subscription host")
        // A user-pasted URL cannot redirect to another host or to intranet
        // resources, even if the subscription provider is malicious.
        val dns = Dns { requested ->
            require(requested.equals(host, ignoreCase = true)) {
                "Unexpected subscription hostname"
            }
            validateDnsAnswers(Dns.SYSTEM.lookup(requested))
        }
        val client = OkHttpClient.Builder()
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.SECONDS)
            .build()

        try {
            val request = Request.Builder().url(url).get()
                .header("Accept", "text/plain,application/octet-stream;q=0.8")
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { response ->
                require(response.code == 200) {
                    "Subscription endpoint returned HTTP status ${response.code}"
                }
                val stream = response.body?.byteStream()
                    ?: error("Subscription response has no body")
                stream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= MAX_BYTES) {
                            "Subscription exceeds 512 KiB"
                        }
                        out.write(buffer, 0, n)
                    }
                    out.toString("UTF-8")
                }
            }
        } finally {
            // Explicit manual fetches should not keep background connection
            // pool threads and DNS caches alive after the request is finished.
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
