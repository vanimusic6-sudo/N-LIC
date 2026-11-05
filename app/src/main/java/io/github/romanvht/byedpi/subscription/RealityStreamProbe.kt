package io.github.romanvht.byedpi.subscription

import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Explicit, small streaming check through the *authenticated* Xray SOCKS5
 * outbound. A full HTTPS request must deliver exactly 512 KiB.
 *
 * This measures HTTPS payload delivery after the initial handshake, not the
 * bare REALITY handshake or the peak network bandwidth. No response body,
 * hostname, server IP or subscription secrets are persisted or logged.
 *
 * Cloudflare speedtest's fixed download endpoint is documented at
 * https://github.com/cloudflare/speedtest
 */
internal object RealityStreamProbe {
    const val BYTES = 512 * 1024
    private const val TARGET = "speed.cloudflare.com"
    private const val PATH = "/__down?bytes=524288"
    private const val TIMEOUT_MS = 7_000
    private const val MAX_DURATION_MS = 25_000L

    internal data class Result(
        val successful: Boolean,
        val receivedBytes: Int,
        val bodyMs: Long?,
        val totalMs: Long?,
        val failure: String? = null,
    ) {
        val mbps: Double?
            get() = if (successful && bodyMs != null && bodyMs > 0)
                (receivedBytes.toDouble() * 8.0) / (bodyMs * 1000.0)
            else null
    }

    internal data class Headers(
        val status: Int,
        val length: Int?,
        val chunked: Boolean,
    )

    private fun line(input: InputStream, maxBytes: Int): String {
        val bytes = ArrayList<Byte>(minOf(maxBytes, 128))
        while (bytes.size < maxBytes) {
            val char = input.read()
            if (char < 0) throw EOFException("Unexpected end of HTTP headers")
            if (char == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes.add(char.toByte())
        }
        throw IllegalStateException("HTTP header line too large")
    }

    /** Standalone for deterministic tests, never accepts external target URLs. */
    internal fun headers(input: InputStream): Headers {
        val start = line(input, 256)
        val status = Regex("^HTTP/1\\.[01] (\\d{3})(?: .*)?$")
            .matchEntire(start)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalStateException("Unexpected HTTP status line")
        var length: Int? = null
        var chunked = false
        var headerBudget = start.length + 2
        var count = 0
        while (true) {
            val current = line(input, 1024)
            headerBudget += current.length + 2
            if (headerBudget > 8192 || ++count > 64)
                throw IllegalStateException("Response headers too large")
            if (current.isEmpty()) break
            val key = current.substringBefore(':').lowercase()
            val value = current.substringAfter(':', "").trim()
            if (key == "content-length") {
                val parsed = value.toIntOrNull()
                    ?: throw IllegalStateException("Invalid Content-Length")
                if (parsed < 0 || (length != null && length != parsed))
                    throw IllegalStateException("Inconsistent Content-Length")
                length = parsed
            }
            if (key == "transfer-encoding") {
                if (!value.equals("chunked", ignoreCase = true))
                    throw IllegalStateException("Unsupported transfer encoding")
                chunked = true
            }
            if (key == "content-encoding" && !value.equals("identity", true))
                throw IllegalStateException("Unexpected content encoding")
        }
        if (chunked && length != null)
            throw IllegalStateException("Ambiguous HTTP framing")
        return Headers(status, length, chunked)
    }

    private fun exact(input: InputStream, expected: Int, expiry: Long): Int {
        val buffer = ByteArray(16 * 1024)
        var received = 0
        while (received < expected) {
            if (System.nanoTime() > expiry)
                throw SocketTimeoutException("Transfer deadline")
            val n = input.read(buffer, 0, minOf(buffer.size, expected - received))
            if (n < 0) throw EOFException("Short transfer")
            if (n == 0) continue
            received += n
        }
        return received
    }

    /** Read only a bounded 512-KiB HTTP response. Never stores its body. */
    internal fun drain(input: InputStream, header: Headers, expiry: Long): Int {
        if (header.status != 200) throw IllegalStateException("HTTP status not 200")
        if (header.length != null && header.length != BYTES)
            throw IllegalStateException("Unexpected Content-Length")
        if (!header.chunked) return exact(input, BYTES, expiry)
        var received = 0
        while (received < BYTES) {
            val chunkHex = line(input, 128).substringBefore(';')
            val size = chunkHex.toIntOrNull(16)
                ?: throw IllegalStateException("Invalid chunk")
            if (size <= 0 || size > BYTES - received)
                throw IllegalStateException("Invalid chunk size")
            received += exact(input, size, expiry)
            if (input.read() != '\r'.code || input.read() != '\n'.code)
                throw IllegalStateException("Invalid chunk framing")
        }
        // A complete chunked response must include a terminating zero chunk.
        if (line(input, 128).substringBefore(';').toIntOrNull(16) != 0)
            throw IllegalStateException("Missing final chunk")
        if (line(input, 128).isNotEmpty())
            throw IllegalStateException("Unexpected chunk trailer")
        return received
    }

    fun run(
        host: String, port: Int, auth: LocalSocksAuth,
        onSocketOpened: (Socket) -> Unit = {},
    ): Result {
        var stage = "SOCKS"
        val began = System.nanoTime()
        val expiry = began + MAX_DURATION_MS * 1_000_000L
        var received = 0
        var bodyMs: Long? = null
        try {
            Socket().use { socket ->
                // The bound service can close this socket on user cancel;
                // no background stream continues after leaving the screen.
                onSocketOpened(socket)
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                TunnelHealthProbe.connectDomain(socket, TARGET, 443, auth)
                stage = "HTTPS_TLS"
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(socket, TARGET, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = TIMEOUT_MS
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    tls.startHandshake()
                    stage = "HTTPS_HEADERS"
                    tls.outputStream.write(
                        ("GET $PATH HTTP/1.1\r\nHost: $TARGET\r\n" +
                            "Accept-Encoding: identity\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII)
                    )
                    tls.outputStream.flush()
                    val metadata = headers(tls.inputStream)
                    stage = "HTTPS_BODY"
                    val transferStarted = System.nanoTime()
                    received = drain(tls.inputStream, metadata, expiry)
                    bodyMs = ((System.nanoTime() - transferStarted) / 1_000_000L)
                        .coerceAtLeast(1L)
                }
            }
            return Result(true, received, bodyMs,
                ((System.nanoTime() - began) / 1_000_000L).coerceAtLeast(1L))
        } catch (error: Exception) {
            // Never surface exception messages; they can reveal host or SNI.
            val category = when (error) {
                is SocketTimeoutException -> "TIMEOUT"
                is EOFException -> "EARLY_EOF"
                else -> if (stage == "HTTPS_TLS") {
                    TunnelHealthProbe.classifyTlsFailure(error).name
                } else stage
            }
            return Result(false, received, bodyMs, null, category)
        }
    }
}
