package io.github.romanvht.byedpi.subscription

/**
 * Safe, bounded LIC-Auto for Xray.
 *
 * The caller evaluates candidates by actually starting an isolated Xray
 * outbound and checking authenticated, certificate-validated HTTPS.
 * Bare TCP-connect time is never mistaken for VPN readiness.
 *
 * Selection runs only for Auto. A separate guarded watchdog handles recovery
 * after a verified tunnel fails; manual selection remains untouched.
 * Only pre-validated profiles are included. No credentials logged.
 */
internal object XrayAutoSelector {
    const val MAX_CANDIDATES = 6

    data class Measurement(val id: String, val elapsedMs: Long)

    fun candidates(
        nodes: List<SubscriptionNode>,
        selectedId: String?,
        bayEnabled: Boolean,
        isSupported: (SubscriptionNode) -> Boolean,
    ): List<SubscriptionNode> {
        // Compiling one Xray JSON can be expensive (WireGuard PEM, VMess
        // import). The UI may hold 500 nodes: examine at most enough to
        // fill the three candidate slots instead of compiling all 500 on
        // every Compose/View re-render or connection attempt.
        val selected = nodes.firstOrNull { it.id == selectedId }
        val ordered = sequence {
            if (selected != null) yield(selected)
            // Try one representative of each transport family before repeating it.
            // The sequence is lazy; costly compile/validation remains bounded.
            yieldAll(nodes.asSequence().filter { it.id != selectedId }.distinctBy { it.protocol })
            yieldAll(nodes.asSequence().filter { it.id != selectedId })
        }
        val chosen = ArrayList<SubscriptionNode>(MAX_CANDIDATES)
        val seen = HashSet<String>()
        for (node in ordered) {
            if (!seen.add(node.id)) continue
            if (bayEnabled && !LICBay.plan(node).supported) continue
            if (!isSupported(node)) continue
            chosen.add(node)
            if (chosen.size == MAX_CANDIDATES) break
        }
        return chosen
    }

    fun best(measurements: List<Measurement>): Measurement? =
        measurements.filter { it.elapsedMs in 1L..60_000L }
            .minWithOrNull(compareBy<Measurement> { it.elapsedMs }.thenBy { it.id })
}
