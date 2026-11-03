package io.github.romanvht.byedpi.utility

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeDiagnosticsTest {
    @Test fun `block page reported as suspicion rather than success`() {
        val blocked = "<html><title>notice</title>Доступ к информационному ресурсу ограничен</html>"
        assertEquals(
            ProbeVerdict.SUSPECT_BLOCK_PAGE,
            ProbeDiagnostics.classifyResponse(200, blocked.toByteArray(Charsets.UTF_8), false)
        )
        assertEquals(
            ProbeVerdict.REACHABLE,
            ProbeDiagnostics.classifyResponse(200, "<html>Real content</html>".toByteArray(), false)
        )
    }

    @Test fun `redirect does not masquerade as reachable destination`() {
        assertEquals(ProbeVerdict.REDIRECTED, ProbeDiagnostics.classifyResponse(302, byteArrayOf(), false))
        assertEquals(ProbeVerdict.HTTP_REJECTED, ProbeDiagnostics.classifyResponse(451, byteArrayOf(), false))
        assertEquals(ProbeVerdict.REACHABLE, ProbeDiagnostics.classifyResponse(204, byteArrayOf(), false))
        assertEquals(ProbeVerdict.EMPTY_RESPONSE, ProbeDiagnostics.classifyResponse(200, byteArrayOf(), false))
        assertEquals(ProbeVerdict.BODY_READ_FAILED, ProbeDiagnostics.classifyResponse(200, byteArrayOf(1), true))
    }

    @Test fun `classify network failure without declaring censorship`() {
        assertEquals(ProbeVerdict.TIMEOUT, ProbeDiagnostics.classifyException(SocketTimeoutException()))
        assertEquals(ProbeVerdict.TLS_FAILURE, ProbeDiagnostics.classifyException(SSLHandshakeException("bad cert")))
        assertEquals(ProbeVerdict.DNS_FAILURE, ProbeDiagnostics.classifyException(UnknownHostException()))
        assertEquals(ProbeVerdict.CONNECTION_FAILURE, ProbeDiagnostics.classifyException(ConnectException()))
        assertEquals(
            ProbeVerdict.DNS_FAILURE,
            ProbeDiagnostics.classifyException(IllegalStateException("wrapped", UnknownHostException()))
        )
        assertEquals(ProbeVerdict.UNKNOWN_FAILURE, ProbeDiagnostics.classifyException(IllegalStateException()))
    }

    @Test fun `summarize verdicts with bounded latency signal`() {
        val summary = ProbeDiagnostics.summary(
            listOf(ProbeVerdict.REACHABLE, ProbeVerdict.REACHABLE, ProbeVerdict.TIMEOUT),
            listOf(19, 35)
        )
        assertEquals(2, summary.successCount)
        assertEquals(2, summary.verdictCounts[ProbeVerdict.REACHABLE])
        assertEquals(1, summary.verdictCounts[ProbeVerdict.TIMEOUT])
        assertEquals(19L, summary.medianSuccessLatencyMs)
        assertNull(ProbeDiagnostics.summary(emptyList(), emptyList()).medianSuccessLatencyMs)
    }
}
