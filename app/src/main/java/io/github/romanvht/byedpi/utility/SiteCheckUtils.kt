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

class SiteCheckUtils(
    private val proxyIp: String,
    private val proxyPort: Int
) {
    companion object {
        // Check reachability without downloading entire videos/pages on mobile data.
        private const val RESPONSE_SAMPLE_BYTES = 4096
        private const val MAX_TIMEOUT_SECONDS = 15L
    }

    suspend fun checkSitesAsync(
        sites: List<String>,
        requestsCount: Int,
        requestTimeout: Long,
        concurrentRequests: Int = 20,
        fullLog: Boolean,
        onSiteChecked: (suspend (String, Int, Int) -> Unit)? = null
    ): List<Pair<String, Int>> {
        val semaphore = Semaphore(concurrentRequests.coerceIn(1, 50))
        return withContext(Dispatchers.IO) {
            sites.map { site ->
                async {
                    semaphore.withPermit {
                        val successCount = checkSiteAccess(site, requestsCount, requestTimeout)
                        coroutineContext.ensureActive()
                        if (fullLog) {
                            onSiteChecked?.invoke(site, successCount, requestsCount)
                        }
                        site to successCount
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkSiteAccess(
        site: String,
        requestsCount: Int,
        timeout: Long
    ): Int = withContext(Dispatchers.IO) {
        var reachableCount = 0

        val formattedUrl = if (site.startsWith("http://") || site.startsWith("https://")) site
        else "https://$site"

        val url = try {
            URL(formattedUrl).also {
                require(it.protocol == "http" || it.protocol == "https")
            }
        } catch (_: Exception) {
            Log.e("SiteChecker", "Invalid proxy test URL")
            return@withContext 0
        }

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyIp, proxyPort))
        val timeoutMs = (timeout.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L).toInt()

        repeat(requestsCount.coerceIn(1, 20)) { attempt ->
            coroutineContext.ensureActive()
            Log.i("SiteChecker", "Attempt ${attempt + 1}/$requestsCount for ${url.host}")

            var connection: HttpURLConnection? = null
            try {
                connection = url.openConnection(proxy) as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Connection", "close")

                val status = connection.responseCode
                var sampledBytes = 0
                var readFailed = false

                // Non-2xx statuses (including 403/451) cannot count as successes.
                // No need to read a large error page just to reject it.
                if (status in 200..299 && status != 204 && status != 205) {
                    try {
                        connection.inputStream.use { input ->
                            val bytes = input.read(ByteArray(RESPONSE_SAMPLE_BYTES))
                            if (bytes > 0) sampledBytes = bytes
                        }
                    } catch (_: IOException) {
                        readFailed = true
                    }
                }

                coroutineContext.ensureActive()
                val verdict = HttpProbeChecks.evaluate(status, sampledBytes, readFailed)
                if (verdict == HttpProbeVerdict.REACHABLE) {
                    reachableCount++
                }
                Log.i(
                    "SiteChecker",
                    "Probe ${url.host}: HTTP $status, bytes=$sampledBytes, verdict=$verdict"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SiteChecker", "Error accessing ${url.host}: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }

        reachableCount
    }
}
