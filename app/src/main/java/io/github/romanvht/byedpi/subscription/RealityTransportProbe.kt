package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * One optional direct TCP reachability check for the SAME configured
 * REALITY server. No proxy, packet mutation or credential disclosure.
 *
 * TCP SYN reachability does not imply a successful REALITY handshake and
 * failure does not prove censorship. Hostname resolution, when needed, is
 * included in elapsedMs and may differ from Xray's resolver.
 */
internal object RealityTransportProbe {
    internal data class Endpoint(val host: String, val port: Int)

    internal data class TcpResult(
        val connected: Boolean,
        val elapsedMs: Long?,
        val failureKind: String? = null,
    )

    internal fun endpoint(config: String): Endpoint? = runCatching {
        val root = JsonParser.parseString(config).asJsonObject
        val outbound = root.getAsJsonArray("outbounds")[0].asJsonObject
        if (outbound.get("protocol")?.asString != "vless")
            return@runCatching null
        val stream = outbound.getAsJsonObject("streamSettings")
            ?: return@runCatching null
        if (stream.get("security")?.asString != "reality" ||
            stream.get("network")?.asString != "raw")
            return@runCatching null
        val vnext = outbound.getAsJsonObject("settings")
            ?.getAsJsonArray("vnext")?.get(0)?.asJsonObject
            ?: return@runCatching null
        val host = vnext.get("address")?.asString ?: return@runCatching null
        val port = vnext.get("port")?.asInt ?: return@runCatching null
        if (host.isBlank() || host.length > 253 || port !in 1..65535)
            return@runCatching null
        Endpoint(host, port)
    }.getOrNull()

    internal fun checkTcp(address: Endpoint): TcpResult {
        val started = System.nanoTime()
        return try {
            Socket().use { it.connect(
                InetSocketAddress(address.host.removeSurrounding("[", "]"),
                    address.port), 3_000)
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000)
                .coerceAtLeast(1)
            TcpResult(connected = true, elapsedMs = elapsed)
        } catch (_: SocketTimeoutException) {
            TcpResult(connected = false, elapsedMs = null, failureKind = "TIMEOUT")
        } catch (_: UnknownHostException) {
            TcpResult(connected = false, elapsedMs = null, failureKind = "DNS")
        } catch (_: Exception) {
            TcpResult(connected = false, elapsedMs = null, failureKind = "CONNECT")
        }
    }
}
