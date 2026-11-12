package io.github.romanvht.byedpi.subscription

import java.io.DataInputStream
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TunnelHealthProbeTest {
    @Test fun `HTTPS probe distinguishes certificate timeout and abort without secrets`() {
        val handshake = javax.net.ssl.SSLHandshakeException("hostname contains private-key")
        handshake.initCause(java.security.cert.CertificateException("credential redacted"))
        assertEquals(TunnelHealthProbe.EgressResult.TLS_CERT,
            TunnelHealthProbe.classifyTlsFailure(handshake))
        assertEquals(TunnelHealthProbe.EgressResult.TLS_TIMEOUT,
            TunnelHealthProbe.classifyTlsFailure(
                java.net.SocketTimeoutException("test domain withheld")))
        assertEquals(TunnelHealthProbe.EgressResult.TLS_HANDSHAKE,
            TunnelHealthProbe.classifyTlsFailure(
                javax.net.ssl.SSLHandshakeException("remote closed")))
        assertEquals(TunnelHealthProbe.EgressResult.TLS_EOF,
            TunnelHealthProbe.classifyTlsFailure(java.io.EOFException()))
        assertEquals(TunnelHealthProbe.EgressResult.TLS_PROTOCOL,
            TunnelHealthProbe.classifyTlsFailure(
                javax.net.ssl.SSLProtocolException("TLS protocol mismatch")))
    }

    @Test fun localProxyRefusalIsDistinguishedFromRemoteTlsFailure() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(TunnelHealthProbe.EgressResult.CONNECT,
            TunnelHealthProbe.probe("127.0.0.1", port))
    }

    @Test fun `verified HTTPS response accepts normal successful sites not only 204`() {
        assertTrue(TunnelHealthProbe.acceptsHttpsResponse("HTTP/1.1 204 No Content\r"))
        assertTrue(TunnelHealthProbe.acceptsHttpsResponse("HTTP/1.1 200 OK"))
        assertTrue(TunnelHealthProbe.acceptsHttpsResponse("HTTP/1.1 301 Moved Permanently"))
        assertFalse(TunnelHealthProbe.acceptsHttpsResponse("HTTP/1.1 403 Forbidden"))
        assertFalse(TunnelHealthProbe.acceptsHttpsResponse("HTTP/1.1 500 Server Error"))
        assertFalse(TunnelHealthProbe.acceptsHttpsResponse("HTTP/1.1 invalid status"))
    }

    @Test fun `probe sends a domain to SOCKS without resolving it on the device`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                val request = executor.submit<String> {
                    server.accept().use { client ->
                        client.soTimeout = 2_000
                        val input = DataInputStream(client.getInputStream())
                        val greeting = ByteArray(3).also(input::readFully)
                        assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
                        val out = client.getOutputStream()
                        out.write(byteArrayOf(5, 0)); out.flush()
                        val header = ByteArray(4).also(input::readFully)
                        assertArrayEquals(byteArrayOf(5, 1, 0, 3), header)
                        val host = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        assertEquals(443, input.readUnsignedShort())
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1)); out.flush()
                        String(host, Charsets.US_ASCII)
                    }
                }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 2_000
                    TunnelHealthProbe.connectDomain(socket, "never-resolve-this.invalid", 443)
                }
                assertEquals("never-resolve-this.invalid", request.get(3, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `private SOCKS listener performs username password handshake`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                val client = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val input = DataInputStream(socket.getInputStream())
                        val greeting = ByteArray(3).also(input::readFully)
                        assertArrayEquals(byteArrayOf(5, 1, 2), greeting)
                        val output = socket.getOutputStream()
                        output.write(byteArrayOf(5, 2))
                        output.flush()
                        assertEquals(1, input.readUnsignedByte())
                        val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        assertEquals("nlic", String(user, Charsets.US_ASCII))
                        assertEquals("private-pass", String(pass, Charsets.US_ASCII))
                        output.write(byteArrayOf(1, 0))
                        output.flush()
                        true
                    }
                }
                assertTrue(TunnelHealthProbe.checkLocal("127.0.0.1",
                    server.localPort, LocalSocksAuth("nlic", "private-pass")))
                assertTrue(client.get(3, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `SOCKS5 CONNECT encodes IPv4 and IPv6 literal addresses without DNS`() {
        val examples = listOf(
            Triple("203.0.113.24", 1, byteArrayOf(203.toByte(), 0, 113, 24)),
            Triple("2001:db8::1", 4, java.net.InetAddress.getByName("2001:db8::1").address)
        )
        for ((host, expectedType, expectedAddress) in examples) {
            val executor = Executors.newSingleThreadExecutor()
            try {
                ServerSocket(0).use { server ->
                    val request = executor.submit<Boolean> {
                        server.accept().use { client ->
                            client.soTimeout = 2_000
                            val input = DataInputStream(client.getInputStream())
                            val greeting = ByteArray(3).also(input::readFully)
                            assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
                            client.getOutputStream().write(byteArrayOf(5, 0))
                            val header = ByteArray(4).also(input::readFully)
                            assertEquals(5, header[0].toInt())
                            assertEquals(expectedType, header[3].toInt())
                            val bytes = ByteArray(expectedAddress.size).also(input::readFully)
                            assertArrayEquals(expectedAddress, bytes)
                            assertEquals(443, input.readUnsignedShort())
                            client.getOutputStream().write(byteArrayOf(
                                5, 0, 0, 1, 127, 0, 0, 1, 0, 1
                            ))
                            true
                        }
                    }
                    Socket("127.0.0.1", server.localPort).use { socket ->
                        socket.soTimeout = 2_000
                        TunnelHealthProbe.connectDomain(socket, host, 443)
                    }
                    assertTrue(request.get(3, TimeUnit.SECONDS))
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun `ByeDPI UDP relay requires a successful SOCKS5 UDP ASSOCIATE reply`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                val handshake = executor.submit<Boolean> {
                    server.accept().use { client ->
                        client.soTimeout = 3_000
                        val inp = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        val hello = ByteArray(3).also(inp::readFully)
                        assertArrayEquals(byteArrayOf(5, 1, 0), hello)
                        out.write(byteArrayOf(5, 0)); out.flush()
                        val request = ByteArray(10).also(inp::readFully)
                        assertArrayEquals(byteArrayOf(5, 3, 0, 1, 127, 0, 0, 1),
                            request.take(8).toByteArray())
                        val port = (request[8].toInt() and 255) * 256 +
                            (request[9].toInt() and 255)
                        assertTrue(port > 0)
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0x12, 0x34))
                        out.flush()
                        true
                    }
                }
                assertTrue(TunnelHealthProbe.checkBayUdp(server.localPort))
                assertTrue(handshake.get(3, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `ByeDPI UDP relay rejects SOCKS5 response failure`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                val task = executor.submit<Unit> {
                    server.accept().use { client ->
                        client.soTimeout = 3_000
                        val inp = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        inp.readFully(ByteArray(3))
                        out.write(byteArrayOf(5, 0)); out.flush()
                        inp.readFully(ByteArray(10))
                        out.write(byteArrayOf(5, 7, 0, 1, 127, 0, 0, 1, 0, 1))
                        out.flush()
                    }
                }
                assertFalse(TunnelHealthProbe.checkBayUdp(server.localPort))
                task.get(3, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `proxy refusal cannot be reported as a healthy tunnel`() {
        ServerSocket(0).use { server ->
            val port = server.localPort
            server.close()
            assertFalse(TunnelHealthProbe.check("127.0.0.1", port))
        }
    }
    @Test fun cancellingProbeClosesStalledSocketWithoutWaitingForTimeout() = kotlinx.coroutines.runBlocking {
        ServerSocket(0).use { server ->
            val accepted = java.util.concurrent.CountDownLatch(1)
            val closed = java.util.concurrent.CountDownLatch(1)
            val peer = kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    accepted.countDown()
                    try {
                        while (socket.inputStream.read() >= 0) { }
                        closed.countDown()
                    } catch (_: Exception) { }
                }
            }
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                TunnelHealthProbe.probeCancellable("127.0.0.1", server.localPort, null, "example.com")
            }
            assertTrue(accepted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            kotlinx.coroutines.withTimeout(1_000L) { job.join() }
            assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
            peer.join(1_000L)
        }
    }
}
