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
    val route: ProbeRoute = ProbeRoute.BOUND_PHYSICAL,
) {
    fun asText(): String = buildString {
        appendLine("N-LIC · network health (direct physical network, opt-in)")
        appendLine("Transport: ${snapshot.type}")
        appendLine("Probe route: ${route.label}")
        if (snapshot.type == AccessType.UNKNOWN) {
            appendLine("IPv4 / IPv6 / NAT64: not observable (NOT a negative test)")
            appendLine("Physical MTU: not observable")
        } else {
            appendLine("IPv4: ${snapshot.hasIpv4}, IPv6: ${snapshot.hasIpv6}, NAT64: ${snapshot.hasNat64}")
            appendLine("Physical MTU reported: ${snapshot.physicalMtu}")
        }
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
        // If more than one physical network is visible while a VPN is the
        // Android default, only use an unambiguously matched transport from
        // the same validated physical candidates. Never route to a guessed
        // network merely because mobile data is enabled.
        val selectedType = NetworkEnvironment.capture(context).type
        val matched = validated.filter { candidate ->
            val caps = manager.getNetworkCapabilities(candidate) ?: return@filter false
            when (selectedType) {
                AccessType.WIFI -> caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                AccessType.CELLULAR -> caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                AccessType.ETHERNET -> caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                else -> false
            }
        }
        return validated.singleOrNull() ?: matched.singleOrNull() ?: physical.singleOrNull()
    }

    suspend fun run(context: Context, compareSocks: Boolean = true): NetworkHealthReport = withContext(Dispatchers.IO) {
        val snapshot = NetworkEnvironment.capture(context)
        val network = try { underlyingNetwork(context) } catch (_: SecurityException) { null }
        // Real devices can report zero visible Networks (including no VPN)
        // while default-route sockets still work. Do not return an empty test!
        // When no physical Network is visible, use ordinary Android sockets,
        // explicitly marking the result UNBOUND rather than claiming direct
        // carrier access. This may transit another system VPN.
        val route = ProbeRoute.forBoundNetwork(network != null)
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
        val notes = NetworkHealthInterpretation.describe(youtubeApi, telegram, small, large)
            .toMutableList()
        if (network == null) notes.add(0, visibilityReason(context))
        val base = NetworkHealthReport(snapshot, checks, notes, route = route)
        if (compareSocks) addProxyComparison(context, base) else base
    }

    suspend fun addProxyComparison(
        context: Context,
        direct: NetworkHealthReport,
    ): NetworkHealthReport = withContext(Dispatchers.IO) {
        if (direct.checks.isEmpty() || direct.proxyChecks.isNotEmpty()) return@withContext direct
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
            listOf(telegramProxied, largeProxied)
        }
        if (viaProxy.isEmpty()) return@withContext direct
        val telegramDirect = direct.checks.firstOrNull { it.target == "Telegram DC2 / TCP 443" }
        val largeDirect = direct.checks.firstOrNull { it.target == "Cloudflare 256 KiB" }
        val extraNotes = if (telegramDirect == null || largeDirect == null) emptyList() else {
            NetworkHealthInterpretation.comparePaths(
                telegramDirect, viaProxy[0], largeDirect, viaProxy[1],
            )
        }
        direct.copy(
            notes = direct.notes + extraNotes,
            proxyChecks = viaProxy,
        )
    }

    /**
     * No SSID, carrier identifier or address is exposed. A VPN can be the
     * visible default while Android hides every physical network from this UID.
     */
    private fun visibilityReason(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "ConnectivityManager unavailable: unable to run direct network tests."
        return try {
            val caps = cm.allNetworks.mapNotNull(cm::getNetworkCapabilities)
            val physical = caps.filter {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            val vpnVisible = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val cellularVisible = physical.count { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            val wifiVisible = physical.count { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            buildString {
                append("Android did not expose a uniquely identifiable physical network to N-LIC. ")
                append("Visible VPN: $vpnVisible; physical networks: ${physical.size}; ")
                append("cellular: $cellularVisible; Wi-Fi: $wifiVisible. ")
                append("Tests used the SYSTEM DEFAULT route instead; their success ")
                append("does not establish a direct carrier path. Any active VPN could ")
                append("carry these packets. If mobile testing, turn off Wi-Fi.")
            }
        } catch (_: SecurityException) {
            "Network capabilities are unavailable. Tests used the Android system default route; VPN bypass is NOT confirmed."
        }
    }

    private fun checkTcp(network: Network?, host: String, label: String): ConnectionCheck {
        val start = SystemClock.elapsedRealtime()
        var reason = "CONNECT_FAILED"
        try {
            // DNS is deliberately resolved on the same underlying interface.
            val addresses = (network?.getAllByName(host) ?: java.net.InetAddress.getAllByName(host)).take(4)
            if (addresses.isEmpty()) {
                return ConnectionCheck(label, false, elapsed(start), "DNS_EMPTY")
            }
            for (ip in addresses) {
                try {
                    (network?.socketFactory?.createSocket() ?: java.net.Socket()).use { socket ->
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
                if (network != null) network.openConnection(url) as HttpURLConnection
                else url.openConnection() as HttpURLConnection
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
