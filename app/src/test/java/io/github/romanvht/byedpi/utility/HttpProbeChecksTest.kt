package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpProbeChecksTest {
    @Test
    fun `refused and blocked HTTP statuses are not successful`() {
        for (status in listOf(301, 302, 403, 404, 429, 451, 500, 503)) {
            assertEquals(
                "HTTP $status should not be counted as available",
                HttpProbeVerdict.HTTP_REJECTED,
                HttpProbeChecks.evaluate(status, bytesReceived = 1024, bodyReadFailed = false)
            )
        }
    }

    @Test
    fun `short successful response is sufficient without downloading everything`() {
        assertEquals(
            HttpProbeVerdict.REACHABLE,
            HttpProbeChecks.evaluate(200, bytesReceived = 1, bodyReadFailed = false)
        )
        assertEquals(
            HttpProbeVerdict.REACHABLE,
            HttpProbeChecks.evaluate(206, bytesReceived = 4096, bodyReadFailed = false)
        )
    }

    @Test
    fun `bodyless success statuses do not require a response body`() {
        assertEquals(HttpProbeVerdict.REACHABLE, HttpProbeChecks.evaluate(204, 0, false))
        assertEquals(HttpProbeVerdict.REACHABLE, HttpProbeChecks.evaluate(205, 0, false))
    }

    @Test
    fun `empty or interrupted body is not a verified success`() {
        assertEquals(HttpProbeVerdict.EMPTY_RESPONSE, HttpProbeChecks.evaluate(200, 0, false))
        assertEquals(HttpProbeVerdict.BODY_READ_FAILED, HttpProbeChecks.evaluate(200, 0, true))
        assertEquals(HttpProbeVerdict.BODY_READ_FAILED, HttpProbeChecks.evaluate(200, 100, true))
    }
}
