package io.github.romanvht.byedpi.subscription

/**
 * Safe, bounded LIC-Auto for Xray.
 *
 * The caller evaluates candidates by actually starting an isolated Xray
 * outbound and checking authenticated, certificate-validated HTTPS.
 * Bare TCP-connect time is never mistaken for VPN readiness.
 *
 * During initial connection only: no background polling, automatic
 * migration or silent replacement of a working user-selected tunnel.
 * Only pre-validated profiles are included. No credentials logged.
 */
internal object XrayAutoSelector {
    const val MAX_CANDIDATES = 3

    data class Measurement(val id: String, val elapsedMs: Long)

    fun candidates(
        nodes: List<SubscriptionNode>,
        selectedId: String?,
        bayEnabled: Boolean,
        isSupported: (SubscriptionNode) -> Boolean,
    ): List<SubscriptionNode> {
        val eligible = nodes.filter { node ->
            (!bayEnabled || LICBay.plan(node).supported) && isSupported(node)
        }.distinctBy { it.id }
        val preferred = eligible.firstOrNull { it.id == selectedId }
        return (listOfNotNull(preferred) + eligible.filterNot { it.id == selectedId })
            .take(MAX_CANDIDATES)
    }

    fun best(measurements: List<Measurement>): Measurement? =
        measurements.filter { it.elapsedMs in 1L..60_000L }
            .minWithOrNull(compareBy<Measurement> { it.elapsedMs }.thenBy { it.id })
}
