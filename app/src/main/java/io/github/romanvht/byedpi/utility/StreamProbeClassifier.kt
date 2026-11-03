package io.github.romanvht.byedpi.utility

/**
 * Result of a *bounded* media transfer, not a determination that an ISP is
 * throttling or censoring. CDN, VPN, congestion and expired tokens can look alike.
 */
internal enum class StreamOutcome {
    COMPLETE,
    SLOW_TRANSFER,
    STALLED_AFTER_DATA,
    TIMEOUT_BEFORE_DATA,
    PREMATURE_EOF,
    SHORT_OBJECT,
    EMPTY_RESPONSE,
    REDIRECTED,
    HTTP_REJECTED,
    NOT_MEDIA,
    INVALID_TARGET,
    CONNECTION_RESET_AFTER_DATA,
    NETWORK_FAILURE,
}

internal object StreamProbeClassifier {
    const val SAMPLE_BYTES = 256 * 1024
    const val LOW_SPEED_BYTES_PER_SECOND = 128 * 1024L

    /** The server may ignore Range; do not assume a 200 response is 256 KiB. */
    fun classify(
        status: Int,
        contentType: String?,
        declaredLength: Long,
        bytesRead: Int,
        timedOut: Boolean,
        reachedEof: Boolean,
        transferDurationMs: Long,
    ): StreamOutcome {
        if (status < 0) {
            return if (timedOut) StreamOutcome.TIMEOUT_BEFORE_DATA else StreamOutcome.NETWORK_FAILURE
        }
        if (status in 300..399) return StreamOutcome.REDIRECTED
        if (status !in 200..299) return StreamOutcome.HTTP_REJECTED
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (type == "text/html" || type == "application/json" || type == "text/plain") {
            return StreamOutcome.NOT_MEDIA
        }
        if (timedOut) {
            return if (bytesRead > 0) StreamOutcome.STALLED_AFTER_DATA
            else StreamOutcome.TIMEOUT_BEFORE_DATA
        }
        if (bytesRead == 0) return StreamOutcome.EMPTY_RESPONSE
        if (bytesRead >= SAMPLE_BYTES) {
            val bps = bytesRead * 1000L / transferDurationMs.coerceAtLeast(1L)
            return if (bps < LOW_SPEED_BYTES_PER_SECOND) StreamOutcome.SLOW_TRANSFER
            else StreamOutcome.COMPLETE
        }
        if (reachedEof && declaredLength >= SAMPLE_BYTES && bytesRead.toLong() < declaredLength) {
            return StreamOutcome.PREMATURE_EOF
        }
        return StreamOutcome.SHORT_OBJECT
    }

    /**
     * Use HTTPS to prevent leaking signed media tokens and restrict probing to
     * the known CDN or to Cloudflare's public, fixed-size speed fixture.
     * Probing an unknown or local host could turn a tester into a network scanner.
     */
    fun isAllowedTarget(scheme: String, host: String, port: Int, path: String): Boolean {
        if (scheme != "https" || port != -1 && port != 443) return false
        val normalized = host.lowercase().trimEnd('.')
        if (normalized == "speed.cloudflare.com") return path == "/__down"
        return (normalized == "googlevideo.com" || normalized.endsWith(".googlevideo.com")) &&
            path.startsWith("/videoplayback")
    }
}
