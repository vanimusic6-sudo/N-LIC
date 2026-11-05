package io.github.romanvht.byedpi.subscription

import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Checks the encrypted outbound, sending the target hostname inside SOCKS5. */
internal data class LocalSocksAuth(val username: String, val password: String)

internal object TunnelHealthProbe {
    private const val TARGET = "www.gstatic.com"
    private const val TIMEOUT = 5_000
    // Probe through the authenticated SOCKS tunnel only. Both Google and
    // Cloudflare connectivity endpoints can be blocked even when a user's
    // VPN server works. A regular HTTPS 200 is equally valid proof of egress.
    // Never follow redirects or send credentials to an external site.
    private val TRUSTED_HTTPS_PROBES = mapOf(
        TARGET to "/generate_204",
        "cp.cloudflare.com" to "/generate_204",
        "example.com" to "/",
        "www.wikipedia.org" to "/",
    )

    // The selected hostname is a fixed allowlisted HTTPS connectivity
    // endpoint, NOT a provider-controlled URL. No cookies or tokens sent.
    /** This result contains only a phase, never a server/hostname or secret. */
    internal enum class EgressResult {
        OK, CONNECT, SOCKS, TLS, HTTP,
        TLS_CERT, TLS_TIMEOUT, TLS_HANDSHAKE, TLS_PROTOCOL, TLS_EOF, TLS_IO
    }

    /**
     * This is TLS between Android's probe and a public HTTPS test host,
     * NOT necessarily the proxy server's VLESS/REALITY handshake.
     * Report only exception categories: messages may contain addresses,
     * SNI, routes, and other private connection details.
     */
    internal fun classifyTlsFailure(error: Exception): EgressResult {
        val hasCertError = generateSequence(error as Throwable?) { it.cause }
            .take(8).any { it is CertificateException || it is SSLPeerUnverifiedException }
        return when {
            hasCertError -> EgressResult.TLS_CERT
            error is SocketTimeoutException -> EgressResult.TLS_TIMEOUT
            error is SSLProtocolException -> EgressResult.TLS_PROTOCOL
            error is SSLHandshakeException -> EgressResult.TLS_HANDSHAKE
            error is EOFException || error is SocketException -> EgressResult.TLS_EOF
            else -> EgressResult.TLS_IO
        }
    }

    fun check(
        host: String, port: Int, auth: LocalSocksAuth? = null,
        target: String = TARGET,
    ): Boolean = probe(host, port, auth, target) == EgressResult.OK

    /**
     * Stage measurements for an HTTPS request through the existing SOCKS5
     * tunnel. IMPORTANT: SOCKS CONNECT is not a direct measurement of the
     * underlying REALITY/TLS handshake; the proxy may acknowledge early.
     * targetTlsMs is TLS to a fixed HTTPS health endpoint, NOT REALITY TLS.
     */
    internal data class DetailedProbe(
        val result: EgressResult,
        val localTcpMs: Long?,
        val socksConnectMs: Long?,
        val targetTlsMs: Long?,
        val httpResponseMs: Long?,
        val totalMs: Long,
    )

    internal fun probe(
        host: String, port: Int, auth: LocalSocksAuth? = null,
        target: String = TARGET,
    ): EgressResult = probeDetailed(host, port, auth, target).result

    internal fun probeDetailed(
        host: String, port: Int, auth: LocalSocksAuth? = null,
        target: String = TARGET,
    ): DetailedProbe {
        val path = requireNotNull(TRUSTED_HTTPS_PROBES[target])
        val started = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        var stage = EgressResult.CONNECT
        var localTcpMs: Long? = null
        var socksMs: Long? = null
        var targetTlsMs: Long? = null
        var httpMs: Long? = null
        val result = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TIMEOUT)
                socket.soTimeout = TIMEOUT
                localTcpMs = elapsed()
                stage = EgressResult.SOCKS
                connectDomain(socket, target, 443, auth)
                socksMs = (elapsed() - localTcpMs!!).coerceAtLeast(1L)
                stage = EgressResult.TLS
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(socket, target, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = TIMEOUT
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    tls.startHandshake()
                    targetTlsMs = (elapsed() - localTcpMs!! - socksMs!!).coerceAtLeast(1L)
                    stage = EgressResult.HTTP
                    tls.outputStream.write(("GET $path HTTP/1.1\r\n" +
                        "Host: $target\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    tls.outputStream.flush()
                    val line = StringBuilder()
                    val input = tls.inputStream
                    while (line.length < 128) {
                        val ch = input.read()
                        if (ch < 0 || ch == 10) break
                        line.append(ch.toChar())
                    }
                    httpMs = (elapsed() - localTcpMs!! - socksMs!! -
                        targetTlsMs!!).coerceAtLeast(1L)
                    if (acceptsHttpsResponse(line.toString())) EgressResult.OK
                    else EgressResult.HTTP
                }
            }
        } catch (error: Exception) {
            // Category only: nested errors may expose addresses, SNI,
            // routes, subscription credentials or private metadata.
            if (stage == EgressResult.TLS) classifyTlsFailure(error) else stage
        }
        return DetailedProbe(result, localTcpMs, socksMs, targetTlsMs,
            httpMs, elapsed())
    }

    /** A cert-verified HTTPS success (200-399) proves end-to-end egress.
     * It must not depend on a particular CDN returning exactly 204.
     */
    internal fun acceptsHttpsResponse(statusLine: String): Boolean {
        val normalized = statusLine.trimEnd('\r')
        val match = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: [^\r\n]*)?$")
            .matchEntire(normalized) ?: return false
        return match.groupValues[1].toInt() in 200..399
    }

    /**
     * Checks only the locally selected VPN endpoint through LIC-Bay.
     * No unrelated public target, no subscription credentials and no DNS
     * requests outside the SOCKS5 connection.
     */
    fun checkBay(proxyPort: Int, endpointHost: String, endpointPort: Int): Boolean {
        if (proxyPort !in 1..65535 || endpointPort !in 1..65535 ||
            endpointHost.isBlank() || endpointHost.length > 253) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 4_000)
                socket.soTimeout = 4_000
                connectDomain(socket, endpointHost, endpointPort)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The pinned ByeDPI implementation implements SOCKS5 UDP ASSOCIATE.
     * Verify that the actual local engine accepts the command BEFORE
     * sing-box starts a QUIC/WireGuard UDP dial. Does not claim that the
     * remote UDP packets crossed a censoring network.
     */
    fun checkBayUdp(proxyPort: Int): Boolean {
        if (proxyPort !in 1..65535) return false
        return try {
            java.net.DatagramSocket(
                InetSocketAddress("127.0.0.1", 0)
            ).use { udp ->
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", proxyPort), TIMEOUT)
                    socket.soTimeout = TIMEOUT
                    negotiate(socket, null)
                    val port = udp.localPort
                    val output = socket.getOutputStream()
                    output.write(byteArrayOf(5, 3, 0, 1, 127, 0, 0, 1,
                        (port ushr 8).toByte(), port.toByte()))
                    output.flush()
                    val input = DataInputStream(socket.getInputStream())
                    check(input.readUnsignedByte() == 5 &&
                        input.readUnsignedByte() == 0 &&
                        input.readUnsignedByte() == 0) {
                        "UDP ASSOCIATE was not accepted"
                    }
                    val addrLength = when (input.readUnsignedByte()) {
                        1 -> 4
                        4 -> 16
                        3 -> input.readUnsignedByte()
                        else -> error("Invalid UDP ASSOCIATE reply")
                    }
                    input.readFully(ByteArray(addrLength))
                    val relayPort = input.readUnsignedShort()
                    check(relayPort in 1..65535)
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Readiness probe for an authenticated local SOCKS5 server. Does not
     * contact an external endpoint and never logs the random secret.
     */
    fun checkLocal(host: String, port: Int, auth: LocalSocksAuth?): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT)
            socket.soTimeout = TIMEOUT
            negotiate(socket, auth)
            true
        }
    } catch (_: Exception) {
        false
    }

    internal fun negotiate(socket: Socket, auth: LocalSocksAuth?) {
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        val method = if (auth == null) 0 else 2
        output.write(byteArrayOf(5, 1, method.toByte()))
        output.flush()
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == method) {
            "SOCKS authentication method not supported"
        }
        if (auth != null) {
            val user = auth.username.toByteArray(Charsets.US_ASCII)
            val pass = auth.password.toByteArray(Charsets.US_ASCII)
            require(user.size in 1..255 && pass.size in 1..255)
            output.write(byteArrayOf(1, user.size.toByte()))
            output.write(user)
            output.write(pass.size)
            output.write(pass)
            output.flush()
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) {
                "SOCKS credentials were rejected"
            }
        }
    }

    internal fun connectDomain(socket: Socket, host: String, port: Int, auth: LocalSocksAuth? = null) {
        require(port in 1..65535)
        val cleanHost = host.removeSurrounding("[", "]")
        val address = when {
            // IPv6 literals must use SOCKS5 ATYP=4. Treating them as a
            // hostname makes some ByeDPI SOCKS builds try to DNS-resolve
            // the colon-separated address and reject it.
            cleanHost.contains(':') -> {
                val literal = java.net.InetAddress.getByName(cleanHost)
                require(literal is java.net.Inet6Address) { "Invalid IPv6 endpoint" }
                byteArrayOf(4) + literal.address
            }
            cleanHost.all { it.isDigit() || it == '.' } -> {
                val parts = cleanHost.split('.')
                require(parts.size == 4 && parts.all {
                    it.isNotEmpty() && it.length <= 3 &&
                        (it.toIntOrNull() ?: -1) in 0..255
                }) { "Invalid IPv4 endpoint" }
                byteArrayOf(1) + parts.map { it.toInt().toByte() }
                    .toByteArray()
            }
            else -> {
                val hostBytes = java.net.IDN.toASCII(cleanHost)
                    .toByteArray(Charsets.US_ASCII)
                require(hostBytes.size in 1..255) { "Invalid SOCKS hostname" }
                byteArrayOf(3, hostBytes.size.toByte()) + hostBytes
            }
        }
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        negotiate(socket, auth)
        output.write(byteArrayOf(5, 1, 0))
        output.write(address)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0 &&
            input.readUnsignedByte() == 0) { "SOCKS connection failed" }
        val addressSize = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> input.readUnsignedByte()
            else -> error("Invalid SOCKS response")
        }
        input.readFully(ByteArray(addressSize + 2))
    }
}
