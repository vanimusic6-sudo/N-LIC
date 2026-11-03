package io.github.romanvht.byedpi.utility

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Evidence about transport outcomes, not a claim that a censor was detected.
 * Labels can be aggregated locally; never write full target URLs or response
 * bodies to persistent logs.
 */
internal enum class ProbeVerdict {
    REACHABLE,
    HTTP_REJECTED,
    REDIRECTED,
    SUSPECT_BLOCK_PAGE,
    EMPTY_RESPONSE,
    BODY_READ_FAILED,
    DNS_FAILURE,
    CONNECTION_FAILURE,
    TLS_FAILURE,
    TIMEOUT,
    NETWORK_FAILURE,
    UNKNOWN_FAILURE,
}

internal data class ProbeSummary(
    val successCount: Int,
    val verdictCounts: Map<ProbeVerdict, Int>,
    val medianSuccessLatencyMs: Long?,
)

internal object ProbeDiagnostics {
    // Conservative, strongly worded signs. This remains a *suspicion*:
    // ordinary web pages may also quote a censorship notification.
    private val blockPageMarkers = listOf(
        "доступ к информационному ресурсу ограничен",
        "доступ к сайту ограничен",
        "доступ к ресурсу ограничен",
        "запрашиваемая страница заблокирована",
        "the requested website has been blocked",
        "this site has been blocked by",
        "access to this resource is restricted",
    )

    fun classifyResponse(status: Int, sample: ByteArray, readFailed: Boolean): ProbeVerdict {
        if (status in 300..399) return ProbeVerdict.REDIRECTED
        if (status !in 200..299) return ProbeVerdict.HTTP_REJECTED
        if (readFailed) return ProbeVerdict.BODY_READ_FAILED
        if (status == 204 || status == 205) return ProbeVerdict.REACHABLE
        if (sample.isEmpty()) return ProbeVerdict.EMPTY_RESPONSE
        // All HTTP bodies are capped to a small sample by the caller.
        val prefix = String(sample, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
        if (blockPageMarkers.any(prefix::contains)) return ProbeVerdict.SUSPECT_BLOCK_PAGE
        return ProbeVerdict.REACHABLE
    }

    fun classifyException(error: Throwable): ProbeVerdict {
        var current: Throwable? = error
        repeat(8) {
            when (current) {
                is UnknownHostException -> return ProbeVerdict.DNS_FAILURE
                is SocketTimeoutException -> return ProbeVerdict.TIMEOUT
                is SSLException -> return ProbeVerdict.TLS_FAILURE
                is ConnectException, is NoRouteToHostException -> return ProbeVerdict.CONNECTION_FAILURE
                is SocketException -> return ProbeVerdict.NETWORK_FAILURE
            }
            current = current?.cause ?: return ProbeVerdict.UNKNOWN_FAILURE
        }
        return ProbeVerdict.UNKNOWN_FAILURE
    }

    fun summary(verdicts: List<ProbeVerdict>, successLatenciesMs: List<Long>): ProbeSummary {
        val sorted = successLatenciesMs.sorted()
        val median = if (sorted.isEmpty()) null else sorted[(sorted.size - 1) / 2]
        return ProbeSummary(
            successCount = verdicts.count { it == ProbeVerdict.REACHABLE },
            verdictCounts = verdicts.groupingBy { it }.eachCount(),
            medianSuccessLatencyMs = median,
        )
    }
}
