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
                !host.startsWith("169.254.") &&
                (!host.contains(':') || publicAddress(InetAddress.getByName(host)))
        } catch (_: Exception) { false }
    }

    /**
     * Reject special-use ranges, not only RFC1918. A malicious subscription
     * host can rebind DNS to an ISP CGNAT gateway, benchmarking subnet, or
     * IPv6 ULA address. All A/AAAA records must be globally routable.
     *
     * This is a deliberately conservative allow-list for global unicast.
     * It does not use network calls and it is enforced at the OkHttp DNS layer.
     */
    internal fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isMulticastAddress) return false
        val a = address.address.map { it.toInt() and 0xff }
        if (a.size == 4) {
            val x = a[0]
            val y = a[1]
            val z = a[2]
            return when {
                x == 0 || x == 10 || x == 127 || x >= 224 -> false
                x == 100 && y in 64..127 -> false       // CGNAT, 100.64/10
                x == 169 && y == 254 -> false          // link-local
                x == 172 && y in 16..31 -> false
                x == 192 && (y == 0 && z in 0..2 ||
                    y == 88 && z == 99 ||
                    y == 168) -> false
                x == 198 && (y in 18..19 || y == 51 && z == 100) -> false
                x == 203 && y == 0 && z == 113 -> false
                else -> true
            }
        }
        if (a.size == 16) {
            // 2000::/3 global unicast, excluding special-use assignments.
            if (a[0] !in 0x20..0x3f) return false
            if (a[0] == 0x20 && a[1] == 0x01 &&
                a[2] <= 0x01) return false              // IETF special-purpose
            if (a[0] == 0x20 && a[1] == 0x01 &&
                a[2] == 0x0d && a[3] == 0xb8) return false // documentation
            if (a[0] == 0x3f && a[1] == 0xff) return false // documentation
            return true
        }
        return false
    }

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
