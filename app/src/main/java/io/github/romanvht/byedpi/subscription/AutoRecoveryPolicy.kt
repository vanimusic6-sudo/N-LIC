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
