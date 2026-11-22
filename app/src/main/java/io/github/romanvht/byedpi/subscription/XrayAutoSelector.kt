package io.github.romanvht.byedpi.subscription

/**
 * Full-catalog LIC-Auto ordering for Xray.
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

    data class Measurement(val id: String, val elapsedMs: Long)

    fun candidates(
        nodes: List<SubscriptionNode>,
        selectedId: String?,
        bayEnabled: Boolean,
        isSupported: (SubscriptionNode) -> Boolean,
    ): List<SubscriptionNode> {
        // No server-count shortlist. The runtime compiles this on IO once;
        // the UI uses any-supported checks instead of compiling the catalog.
        val selected = nodes.firstOrNull { it.id == selectedId }
        val ordered = sequence {
            if (selected != null) yield(selected)
            // Try one representative of each transport family before repeating it.
            // The sequence is lazy; costly compile/validation remains bounded.
            yieldAll(nodes.asSequence().filter { it.id != selectedId }.distinctBy {
                // XHTTP has a materially different HTTP transport even
                // though its application protocol is still VLESS.
                if (XrayXhttpCompiler.isXhttp(it)) "VLESS-XHTTP" else it.protocol
            })
            yieldAll(nodes.asSequence().filter { it.id != selectedId })
        }
        val chosen = ArrayList<SubscriptionNode>()
        val seen = HashSet<String>()
        for (node in ordered) {
            if (!seen.add(node.id)) continue
            if (bayEnabled && !LICBay.plan(node).supported) continue
            if (!isSupported(node)) continue
            chosen.add(node)
        }
        return chosen
    }

    /**
     * On a failed Auto reconnect, move the encrypted backup route to a
     * different server. Xray continues to measure the entire catalog;
     * only its fallbackTag changes when the probe URL is filtered.
     *
     * Candidate ordering puts distinct transport families before repeats.
     * Neither the user's selected node nor its encrypted profile is edited.
     */
    internal fun onRecovery(
        candidates: List<SubscriptionNode>,
        failedRounds: Int,
    ): List<SubscriptionNode> {
        if (failedRounds <= 0 || candidates.size <= 1) return candidates
        val backupIndex = (failedRounds - 1) % (candidates.size - 1) + 1
        val backup = candidates[backupIndex]
        return listOf(backup) + candidates.filterNot { it.id == backup.id }
    }

    fun best(measurements: List<Measurement>): Measurement? =
        measurements.filter { it.elapsedMs in 1L..60_000L }
            .minWithOrNull(compareBy<Measurement> { it.elapsedMs }.thenBy { it.id })
}
