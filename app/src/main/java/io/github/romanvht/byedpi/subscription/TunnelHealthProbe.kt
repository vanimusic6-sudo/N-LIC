package io.github.romanvht.byedpi.subscription

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Checks the encrypted outbound, sending the target hostname inside SOCKS5. */
internal data class LocalSocksAuth(val username: String, val password: String)

internal object TunnelHealthProbe {
    private const val TARGET = "www.gstatic.com"
    private const val TIMEOUT = 4_000
    private val TRUSTED_204_TARGETS = setOf(TARGET, "cp.cloudflare.com")

    // The selected hostname is a fixed allowlisted HTTPS connectivity
    // endpoint, NOT a provider-controlled URL. No cookies or tokens sent.
    fun check(
        host: String, port: Int, auth: LocalSocksAuth? = null,
        target: String = TARGET,
    ): Boolean = try {
        require(target in TRUSTED_204_TARGETS)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT)
            socket.soTimeout = TIMEOUT
            connectDomain(socket, target, 443, auth)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(socket, target, 443, true) as SSLSocket).use { tls ->
                tls.soTimeout = TIMEOUT
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                tls.startHandshake()
                tls.outputStream.write(("GET /generate_204 HTTP/1.1\r\n" +
                    "Host: $target\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                tls.outputStream.flush()
                // A bounded first line suffices; no cookies, body or redirects.
                val line = StringBuilder()
                val input = tls.inputStream
                while (line.length < 128) {
                    val c = input.read()
                    if (c < 0 || c == 10) break
                    line.append(c.toChar())
                }
                line.toString().trimEnd('\r') in setOf("HTTP/1.1 204 No Content", "HTTP/1.0 204 No Content") ||
                    line.toString().matches(Regex("HTTP/1\\.[01] 204(?: [^\\r\\n]*)?\\r?"))
            }
        }
    } catch (_: Exception) {
        false // TLS, credentials and provider errors never escape into logs.
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
