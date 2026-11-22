package io.github.romanvht.byedpi.subscription

/**
 * Guardrails for continuous LIC-Auto.
 *
 * Retry only failed, previously established Auto VPN sessions or their
 * follow-up egress scans. Broken imports, invalid credentials, missing
 * engine binaries and native crashes must never trigger a hot restart loop.
 * The supervisor holds the opt-in and command-token cancellation state.
 */
internal object AutoRecoveryPolicy {
    private val backoffMs = longArrayOf(4_000L, 8_000L, 16_000L,
        30_000L, 60_000L, 120_000L, 180_000L)

    fun delayMs(attempt: Int): Long =
        backoffMs[attempt.coerceIn(0, backoffMs.lastIndex)]

    /**
     * +/-20% bounded jitter avoids synchronized reconnection storms after
     * carrier outages. No randomness is stored or transmitted, and the
     * retry ceiling stays at 180 seconds even for unbounded attempts.
     */
    fun jitteredDelayMs(attempt: Int, entropy: Long): Long {
        val base = delayMs(attempt)
        val spread = base / 5L
        val mixed = entropy xor (entropy ushr 32) xor
            (attempt.coerceAtLeast(0).toLong() * -7046029254386353131L)
        val offset = ((mixed and Long.MAX_VALUE) % (spread * 2L + 1L)) - spread
        return (base + offset).coerceIn(1_000L, 180_000L)
    }

    fun retryable(code: String?): Boolean = code in setOf(
        "AUTO_EGRESS_LOST",
        "AUTO_NO_WORKING_SERVER",
        "REALITY_EGRESS",
        "REMOTE_TEST",
        "REMOTE_CONNECT",
        "REMOTE_SOCKS",
        "REMOTE_TLS",
        "REMOTE_HTTP",
        "REMOTE_TLS_TIMEOUT",
        "REMOTE_TLS_HANDSHAKE",
        "REMOTE_TLS_EOF",
        "REMOTE_TLS_IO",
        "AUTO_PROBE",
    )
}
