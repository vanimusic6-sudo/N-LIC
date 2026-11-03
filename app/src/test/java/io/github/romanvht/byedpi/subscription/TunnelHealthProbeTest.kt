package io.github.romanvht.byedpi.subscription

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TunnelHealthProbeTest {
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

    @Test fun `proxy refusal cannot be reported as a healthy tunnel`() {
        ServerSocket(0).use { server ->
            val port = server.localPort
            server.close()
            assertFalse(TunnelHealthProbe.check("127.0.0.1", port))
        }
    }
}
