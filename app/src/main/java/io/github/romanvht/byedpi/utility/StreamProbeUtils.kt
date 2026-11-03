package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.StreamProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * One opt-in, bounded GET through the same SOCKS5 connection as the native
 * N-LIC proxy. Unlike a 4 KiB homepage probe, this tests a real video range.
 *
 * Never log/store the full URL: Googlevideo signed links contain sensitive,
 * expiring query tokens. Caller must remove the preference after reading it.
 */
internal class StreamProbeUtils(
    private val proxyHost: String,
    private val proxyPort: Int,
) {
    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        private const val HARD_MAX_DURATION_MS = 25_000L
    }

    suspend fun test(
        rawUrl: String,
        timeoutSeconds: Long,
    ): StreamProbeResult = withContext(Dispatchers.IO) {
        val url = try {
            require(rawUrl.length in 1..8192)
            URL(rawUrl).also {
                require(it.userInfo == null)
                require(StreamProbeClassifier.isAllowedTarget(it.protocol, it.host, it.port, it.path))
            }
        } catch (_: Exception) {
            return@withContext result(StreamOutcome.INVALID_TARGET)
        }
        val start = System.nanoTime()
        var status = -1
        var contentType: String? = null
        var declaredLength = -1L
        var bytesRead = 0
        var firstByteMs: Long? = null
        var reachedEof = false
        var timedOut = false
        var failed = false
        var connection: HttpURLConnection? = null
        val timeLimitMs = timeoutSeconds.coerceIn(2L, 15L) * 1000L

        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
            connection = url.openConnection(proxy) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeLimitMs.toInt()
            // Individual reads must not freeze the strategy engine indefinitely.
            connection.readTimeout = timeLimitMs.toInt()
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=0-${StreamProbeClassifier.SAMPLE_BYTES - 1}")
            connection.setRequestProperty("Connection", "close")
            status = connection.responseCode
            contentType = connection.contentType
            declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

            if (status in 200..299) {
                val stream = connection.inputStream
                stream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (bytesRead < StreamProbeClassifier.SAMPLE_BYTES) {
                        coroutineContext.ensureActive()
                        if ((System.nanoTime() - start) / 1_000_000L >= HARD_MAX_DURATION_MS) {
                            timedOut = true
                            break
                        }
                        val read = try {
                            input.read(buffer, 0, minOf(buffer.size, StreamProbeClassifier.SAMPLE_BYTES - bytesRead))
                        } catch (_: SocketTimeoutException) {
                            timedOut = true
                            break
                        }
                        if (read == -1) {
                            reachedEof = true
                            break
                        }
                        if (read == 0) continue
                        if (firstByteMs == null) firstByteMs = (System.nanoTime() - start) / 1_000_000L
                        bytesRead += read
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            timedOut = true
        } catch (_: IOException) {
            failed = true
        } catch (_: Exception) {
            failed = true
        } finally {
            connection?.disconnect()
        }

        val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        val transferMs = (elapsedMs - (firstByteMs ?: 0L)).coerceAtLeast(1L)
        val verdict = if (failed) {
            if (bytesRead > 0) StreamOutcome.CONNECTION_RESET_AFTER_DATA
            else StreamOutcome.NETWORK_FAILURE
        } else {
            StreamProbeClassifier.classify(
                status, contentType, declaredLength, bytesRead,
                timedOut, reachedEof, transferMs
            )
        }

        StreamProbeResult(
            outcome = verdict.name,
            host = url.host,
            bytesRead = bytesRead,
            expectedBytes = StreamProbeClassifier.SAMPLE_BYTES,
            durationMs = elapsedMs,
            firstByteMs = firstByteMs,
            transferBytesPerSecond = if (firstByteMs == null) null
                else bytesRead * 1000L / transferMs,
            httpStatus = status,
        )
    }

    private fun result(outcome: StreamOutcome) = StreamProbeResult(
        outcome = outcome.name,
        host = "",
        bytesRead = 0,
        expectedBytes = StreamProbeClassifier.SAMPLE_BYTES,
        durationMs = 0L,
    )
}
