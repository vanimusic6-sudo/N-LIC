package io.github.romanvht.byedpi.utility

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Small, bounded reachability probes. These are *not* proofs of evasion:
 * a legitimate HTTP 200 may still be a captive portal or an unknown block page.
 */
internal class SiteCheckUtils(
    private val proxyIp: String,
    private val proxyPort: Int
) {
    companion object {
        private const val RESPONSE_SAMPLE_BYTES = 4096
        private const val MAX_TIMEOUT_SECONDS = 15L
        private const val MAX_ATTEMPTS = 20
    }

    suspend fun checkSitesAsync(
        sites: List<String>,
        requestsCount: Int,
        requestTimeout: Long,
        concurrentRequests: Int = 20,
        fullLog: Boolean,
        onSiteChecked: (suspend (String, Int, Int) -> Unit)? = null,
        onSiteDiagnostics: (suspend (String, ProbeSummary) -> Unit)? = null,
    ): List<Pair<String, Int>> {
        val semaphore = Semaphore(concurrentRequests.coerceIn(1, 50))
        val count = requestsCount.coerceIn(1, MAX_ATTEMPTS)
        return withContext(Dispatchers.IO) {
            sites.map { site ->
                async {
                    semaphore.withPermit {
                        val summary = checkSiteAccess(site, count, requestTimeout)
                        coroutineContext.ensureActive()
                        if (fullLog) onSiteChecked?.invoke(site, summary.successCount, count)
                        onSiteDiagnostics?.invoke(site, summary)
                        site to summary.successCount
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkSiteAccess(
        site: String,
        requestsCount: Int,
        timeout: Long,
    ): ProbeSummary = withContext(Dispatchers.IO) {
        val formattedUrl = if (site.startsWith("http://", true) || site.startsWith("https://", true)) {
            site
        } else {
            "https://$site"
        }
        val url = try {
            URL(formattedUrl).also {
                require(it.protocol == "http" || it.protocol == "https")
                require(it.host.isNotBlank())
            }
        } catch (_: Exception) {
            Log.w("SiteChecker", "Invalid proxy test URL (target omitted)")
            return@withContext ProbeDiagnostics.summary(
                List(requestsCount) { ProbeVerdict.UNKNOWN_FAILURE }, emptyList()
            )
        }

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyIp, proxyPort))
        val timeoutMs = (timeout.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L).toInt()
        val verdicts = ArrayList<ProbeVerdict>(requestsCount)
        val successesMs = ArrayList<Long>(requestsCount)

        repeat(requestsCount) {
            coroutineContext.ensureActive()
            var connection: HttpURLConnection? = null
            val startNanos = System.nanoTime()
            try {
                connection = url.openConnection(proxy) as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                // A redirect to a block page or captive portal is NOT success
                // at the original destination. Never silently follow it.
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")

                val status = connection.responseCode
                var sample = byteArrayOf()
                var readFailed = false
                if (status in 200..299 && status != 204 && status != 205) {
                    try {
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(RESPONSE_SAMPLE_BYTES)
                            val bytes = input.read(buffer)
                            if (bytes > 0) sample = buffer.copyOf(bytes)
                        }
                    } catch (_: IOException) {
                        readFailed = true
                    }
                }
                coroutineContext.ensureActive()
                val verdict = ProbeDiagnostics.classifyResponse(status, sample, readFailed)
                verdicts.add(verdict)
                if (verdict == ProbeVerdict.REACHABLE) {
                    successesMs.add((System.nanoTime() - startNanos) / 1_000_000L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                verdicts.add(ProbeDiagnostics.classifyException(e))
            } finally {
                connection?.disconnect()
            }
        }
        val summary = ProbeDiagnostics.summary(verdicts, successesMs)
        // Diagnostic counts only; no URL path, query, body or proxy arguments.
        Log.d("SiteChecker", "Probe ${url.host}: ${summary.verdictCounts}")
        summary
    }
}
