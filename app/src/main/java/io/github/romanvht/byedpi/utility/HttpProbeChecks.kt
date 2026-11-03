package io.github.romanvht.byedpi.utility

/**
 * A successful HTTPS handshake is not the same as an accessible page.
 * Keep the HTTP probe verdict separate from the network I/O so it can be tested.
 *
 * REACHABLE means that the request received a successful HTTP status and some
 * response body (or an explicitly bodyless 204/205). It does NOT prove that the
 * requested service is unblocked: HTTP 200 block pages still require further
 * inspection, which is deliberately out of scope for this basic probe.
 */
internal enum class HttpProbeVerdict {
    REACHABLE,
    HTTP_REJECTED,
    EMPTY_RESPONSE,
    BODY_READ_FAILED,
}

internal object HttpProbeChecks {
    fun evaluate(
        statusCode: Int,
        bytesReceived: Int,
        bodyReadFailed: Boolean,
    ): HttpProbeVerdict {
        if (statusCode !in 200..299) return HttpProbeVerdict.HTTP_REJECTED
        if (bodyReadFailed) return HttpProbeVerdict.BODY_READ_FAILED
        if (statusCode == 204 || statusCode == 205 || bytesReceived > 0) {
            return HttpProbeVerdict.REACHABLE
        }
        return HttpProbeVerdict.EMPTY_RESPONSE
    }
}
