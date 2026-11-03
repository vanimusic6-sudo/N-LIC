package io.github.romanvht.byedpi.subscription

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Checks the encrypted outbound, sending the target hostname inside SOCKS5. */
internal object TunnelHealthProbe {
    private const val TARGET = "www.gstatic.com"
    private const val TIMEOUT = 1_500

    fun check(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT)
            socket.soTimeout = TIMEOUT
            connectDomain(socket, TARGET, 443)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(socket, TARGET, 443, true) as SSLSocket).use { tls ->
                tls.soTimeout = TIMEOUT
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                tls.startHandshake()
                tls.outputStream.write(("GET /generate_204 HTTP/1.1\r\n" +
                    "Host: $TARGET\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
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
                socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 2500)
                socket.soTimeout = 2500
                connectDomain(socket, endpointHost, endpointPort)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun connectDomain(socket: Socket, host: String, port: Int) {
        val domain = host.toByteArray(Charsets.US_ASCII)
        require(domain.size in 1..255 && port in 1..65535)
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) {
            "SOCKS authentication failed"
        }
        output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()))
        output.write(domain)
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
