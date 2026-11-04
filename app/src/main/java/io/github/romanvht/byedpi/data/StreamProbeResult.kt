package io.github.romanvht.byedpi.data

/**
 * Contains only a host and measured byte counters, never a signed video URL,
 * its query parameters, proxy credentials, or response body.
 */
data class StreamProbeResult(
    val outcome: String,
    val host: String,
    val bytesRead: Int,
    val expectedBytes: Int,
    val durationMs: Long,
    val firstByteMs: Long? = null,
    val transferBytesPerSecond: Long? = null,
    val httpStatus: Int = -1,
)
