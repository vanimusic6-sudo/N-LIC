package io.github.romanvht.byedpi.utility

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import io.github.romanvht.byedpi.services.NativeEngine
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

internal data class NetworkHealthReport(
    val snapshot: AccessSnapshot,
    val checks: List<ConnectionCheck>,
    val notes: List<String>,
    val proxyChecks: List<ConnectionCheck> = emptyList(),
) {
    fun asText(): String = buildString {
        appendLine("N-LIC · network health (direct physical network, opt-in)")
        appendLine("Transport: ${snapshot.type}")
        appendLine("IPv4: ${snapshot.hasIpv4}, IPv6: ${snapshot.hasIpv6}, NAT64: ${snapshot.hasNat64}")
        appendLine("Physical MTU reported: ${snapshot.physicalMtu}")
        for (check in checks + proxyChecks) {
            appendLine("${check.target}: ${check.detail} · ${check.latencyMs} ms" +
                if (check.bytes > 0) " · ${check.bytes} bytes" else "")
        }
        appendLine()
        notes.forEach { appendLine("• $it") }
        appendLine("These measurements alone cannot prove censorship or application-level support.")
    }
}

/**
 * Read-only tests bound to the *physical* Android network. Never test arbitrary
 * user-supplied hosts, follow redirects or retain bodies. Tests deliberately
 * bypass N-LIC to isolate whether the carrier path itself is healthy.
 */
internal object NetworkHealthUtils {
    private const val MAX_DOWNLOAD_BYTES = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 3_500

    fun underlyingNetwork(context: Context): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        fun eligible(network: Network): Boolean {
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val active = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) manager.activeNetwork else null
        if (active != null && eligible(active)) return active
        val physical = manager.allNetworks.filter(::eligible)
        val validated = physical.filter { network ->
            manager.getNetworkCapabilities(network)?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true
        }
        // No ambiguous choice of Wi-Fi when a mobile carrier is also active.
        return validated.singleOrNull() ?: physical.singleOrNull()
    }

    suspend fun run(context: Context): NetworkHealthReport = withContext(Dispatchers.IO) {
        val snapshot = NetworkEnvironment.capture(context)
        val network = try { underlyingNetwork(context) } catch (_: SecurityException) { null }
        if (network == null) {
            return@withContext NetworkHealthReport(
                snapshot,
                emptyList(),
                listOf("Android did not expose a unique active physical network. " +
                    "Disable other VPN clients or switch off Wi-Fi before testing mobile data."),
            )
        }
        coroutineContext.ensureActive()

        // Telegram production DC IPs are example DCs, not a guarantee that the
        // Telegram client currently uses them. Check two separate datacentres.
        val youtube = checkTcp(network, "i.ytimg.com", "YouTube thumbnails / TCP 443")
        coroutineContext.ensureActive()
        val youtubeApi = checkTcp(network, "www.youtube.com", "YouTube website / TCP 443")
        coroutineContext.ensureActive()
        val telegram = checkTcp(network, "149.154.167.51", "Telegram DC2 / TCP 443")
        coroutineContext.ensureActive()
        val telegramAlt = checkTcp(network, "91.108.56.130", "Telegram DC5 / TCP 443")
        coroutineContext.ensureActive()
        val small = checkDownload(network, 16 * 1024, "Cloudflare 16 KiB")
        coroutineContext.ensureActive()
        val large = checkDownload(network, MAX_DOWNLOAD_BYTES, "Cloudflare 256 KiB")
        val checks = listOf(youtube, youtubeApi, telegram, telegramAlt, small, large)
        val notes = NetworkHealthInterpretation.describe(youtubeApi, telegram, small, large).toMutableList()
        val localProxy = if (NativeEngine.running(context) != null) {
            val (host, portText) = context.getPreferences().getProxyIpAndPort()
            val hostAddress = when (host) {
                "0.0.0.0" -> "127.0.0.1"
                "::", "[::]" -> "::1"
                else -> host
            }
            val port = portText.toIntOrNull()
            if (port != null && port in 1..65535) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(hostAddress, port))
            } else null
        } else null
        val viaProxy = if (localProxy == null) emptyList() else {
            coroutineContext.ensureActive()
            val telegramProxied = checkSocksTcp(localProxy, "149.154.167.51", "N-LIC SOCKS / Telegram DC2")
            coroutineContext.ensureActive()
            val largeProxied = checkDownload(
                null, MAX_DOWNLOAD_BYTES, "N-LIC SOCKS / Cloudflare 256 KiB", localProxy
            )
            notes.addAll(NetworkHealthInterpretation.comparePaths(
                telegram, telegramProxied, large, largeProxied
            ))
            listOf(telegramProxied, largeProxied)
        }
        NetworkHealthReport(snapshot, checks, notes, viaProxy)
    }

    private fun checkTcp(network: Network, host: String, label: String): ConnectionCheck {
        val start = SystemClock.elapsedRealtime()
        var reason = "CONNECT_FAILED"
        try {
            // DNS is deliberately resolved on the same underlying interface.
            val addresses = network.getAllByName(host).take(4)
            if (addresses.isEmpty()) {
                return ConnectionCheck(label, false, elapsed(start), "DNS_EMPTY")
            }
            for (ip in addresses) {
                try {
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
                        return ConnectionCheck(
                            label, true, elapsed(start),
                            if (ip is java.net.Inet6Address) "TCP_OK_IPV6" else "TCP_OK_IPV4",
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    reason = "TCP_TIMEOUT"
                } catch (_: IOException) {
                    reason = "CONNECT_FAILED"
                }
            }
        } catch (_: java.net.UnknownHostException) {
            reason = "DNS_FAILURE"
        } catch (_: SecurityException) {
            reason = "NETWORK_PERMISSION"
        } catch (_: Exception) {
            reason = "NETWORK_FAILURE"
        }
        return ConnectionCheck(label, false, elapsed(start), reason)
    }

    private fun checkSocksTcp(proxy: Proxy, host: String, label: String): ConnectionCheck {
        val start = SystemClock.elapsedRealtime()
        return try {
            java.net.Socket(proxy).use { socket ->
                socket.connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS)
                ConnectionCheck(label, true, elapsed(start), "SOCKS_CONNECT_OK")
            }
        } catch (_: SocketTimeoutException) {
            ConnectionCheck(label, false, elapsed(start), "SOCKS_CONNECT_TIMEOUT")
        } catch (_: IOException) {
            ConnectionCheck(label, false, elapsed(start), "SOCKS_CONNECT_FAILURE")
        } catch (_: Exception) {
            ConnectionCheck(label, false, elapsed(start), "SOCKS_UNKNOWN_FAILURE")
        }
    }

    private fun checkDownload(
        network: Network?,
        bytes: Int,
        label: String,
        proxy: Proxy? = null,
    ): ConnectionCheck {
        val start = SystemClock.elapsedRealtime()
        val url = URL("https://speed.cloudflare.com/__down?bytes=$bytes")
        var connection: HttpURLConnection? = null
        var received = 0
        return try {
            connection = if (proxy == null) {
                requireNotNull(network).openConnection(url) as HttpURLConnection
            } else url.openConnection(proxy) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            val status = connection.responseCode
            if (status !in 200..299) {
                ConnectionCheck(label, false, elapsed(start), "HTTP_$status")
            } else {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val deadline = start + 14_000
                    while (received < bytes && SystemClock.elapsedRealtime() < deadline) {
                        val count = input.read(buffer, 0, minOf(buffer.size, bytes - received))
                        if (count < 0) break
                        received += count
                    }
                }
                val complete = received >= bytes
                ConnectionCheck(
                    label, complete, elapsed(start),
                    if (complete) "BODY_COMPLETE" else "EARLY_EOF_OR_STALL",
                    received,
                )
            }
        } catch (_: SocketTimeoutException) {
            ConnectionCheck(
                label, false, elapsed(start),
                if (received > 0) "STALLED_AFTER_BYTES" else "TIMEOUT_BEFORE_BYTES",
                received,
            )
        } catch (_: SSLException) {
            ConnectionCheck(label, false, elapsed(start), "TLS_FAILURE", received)
        } catch (_: IOException) {
            ConnectionCheck(label, false, elapsed(start), "NETWORK_FAILURE", received)
        } catch (_: Exception) {
            ConnectionCheck(label, false, elapsed(start), "UNKNOWN_FAILURE", received)
        } finally {
            connection?.disconnect()
        }
    }

    private fun elapsed(startMs: Long): Long =
        (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
}
