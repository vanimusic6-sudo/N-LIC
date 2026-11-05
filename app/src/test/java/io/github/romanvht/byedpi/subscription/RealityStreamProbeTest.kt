package io.github.romanvht.byedpi.subscription

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityStreamProbeTest {
    @Test fun fixedSizeBodyMustArriveInFull() {
        val body = ByteArray(RealityStreamProbe.BYTES) { (it % 127).toByte() }
        val headers = RealityStreamProbe.Headers(200, RealityStreamProbe.BYTES, false)
        assertEquals(RealityStreamProbe.BYTES,
            RealityStreamProbe.drain(ByteArrayInputStream(body),
                headers, Long.MAX_VALUE))
    }

    @Test(expected = java.io.EOFException::class)
    fun shortBodyNeverCountsAsSuccess() {
        RealityStreamProbe.drain(
            ByteArrayInputStream(ByteArray(RealityStreamProbe.BYTES - 1)),
            RealityStreamProbe.Headers(200, RealityStreamProbe.BYTES, false),
            Long.MAX_VALUE
        )
    }

    @Test fun chunkedTransfersRequireCompleteTerminator() {
        val first = "40000\r\n".toByteArray(Charsets.US_ASCII)
        val data = ByteArray(RealityStreamProbe.BYTES)
        val finish = "\r\n40000\r\n".toByteArray(Charsets.US_ASCII)
        val last = "\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val bytes = first + data.copyOfRange(0, 0x40000) +
            finish + data.copyOfRange(0x40000, data.size) + last
        val result = RealityStreamProbe.drain(ByteArrayInputStream(bytes),
            RealityStreamProbe.Headers(200, null, true), Long.MAX_VALUE)
        assertEquals(RealityStreamProbe.BYTES, result)
    }

    @Test fun httpHeadersRejectContradictoryLengths() {
        val headers = ("HTTP/1.1 200 OK\r\nContent-Length: 524288\r\n" +
            "Content-Length: 12\r\n\r\n")
        assertTrue(runCatching {
            RealityStreamProbe.headers(ByteArrayInputStream(
                headers.toByteArray(Charsets.US_ASCII)))
        }.isFailure)
    }

    @Test fun httpHeadersAcceptOnlyCleanLengthAndStatus() {
        val headers = ("HTTP/1.1 200 OK\r\nContent-Length: 524288\r\n\r\n")
        val parsed = RealityStreamProbe.headers(
            ByteArrayInputStream(headers.toByteArray(Charsets.US_ASCII)))
        assertEquals(200, parsed.status)
        assertEquals(RealityStreamProbe.BYTES, parsed.length)
        assertFalse(parsed.chunked)
        assertTrue(runCatching {
            RealityStreamProbe.drain(ByteArrayInputStream(ByteArray(100)),
                RealityStreamProbe.Headers(302, null, false), Long.MAX_VALUE)
        }.isFailure)
    }

    @Test fun summaryDoesNotClaimDpiFromOneSlowTransfer() {
        val okay = RealityStreamProbe.Result(true, 524288, 400L, 800L)
        val bad = RealityStreamProbe.Result(false, 12000, null, null, "EARLY_EOF")
        val summary = RealityStreamComparison.summarize(listOf(
            RealityStreamComparison.Sample("profile", okay),
            RealityStreamComparison.Sample("firefox", bad),
            RealityStreamComparison.Sample("chrome", okay)
        ))
        assertTrue(summary.compact.contains("2/3"))
        assertTrue(summary.details.contains("не доказывает блокировку DPI"))
        assertTrue(summary.details.contains("EARLY_EOF"))
        assertEquals(10.48576, okay.mbps!!, 0.00001)
    }
}
