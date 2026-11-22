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
    private val backoffMs = longArrayOf(1_000L, 3_000L, 8_000L,
        30_000L, 60_000L, 120_000L, 180_000L)

    fun delayMs(attempt: Int): Long =
        backoffMs[attempt.coerceIn(0, backoffMs.lastIndex)]

    /**
     * Add 20% bounded delay jitter after an operator-wide outage. Mixing
     * entropy protects adjacent monotonic timer values from collapsing
     * to one repeated delay. This is not cryptographic randomness.
     */
    fun jitteredDelayMs(attempt: Int, entropy: Long): Long {
        val base = delayMs(attempt)
        val spread = base / 5L
        var bits = entropy xor
            (attempt.coerceAtLeast(0).toLong() * -7046029254386353131L)
        bits = (bits xor (bits ushr 30)) * -4658895280553007687L
        bits = (bits xor (bits ushr 27)) * -7723592293110705685L
        bits = bits xor (bits ushr 31)
        val offset = ((bits and Long.MAX_VALUE) % (spread * 2L + 1L)) - spread
        return (base + offset).coerceIn(800L, 180_000L)
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
        "REMOTE_TEST_TIMEOUT",
        "XRAY_HTTPS_TIMEOUT",
    )
}
