package io.github.romanvht.byedpi.subscription

import io.github.romanvht.byedpi.utility.AccessSnapshot
import java.util.LinkedHashMap

/**
 * LIC-Fork: bounded local transport selection, never homemade encryption.
 * The chain is two independently authenticated Xray outbounds via dialerProxy:
 * client -> bridge -> exit -> destination. Both endpoints must be user-owned.
 */
internal data class LICForkPath(
    val exit: SubscriptionNode,
    val bridge: SubscriptionNode? = null,
) {
    val key: String get() = if (bridge == null) "direct:" + exit.id
        else "chain:" + bridge.id + ":" + exit.id
    val isChain: Boolean get() = bridge != null
}

internal data class LICForkBatch(
    val direct: List<LICForkPath>,
    val chained: List<LICForkPath>,
)

internal object LICForkPlanner {
    const val MAX_SCAN = 24
    const val MAX_DIRECT = 3
    const val MAX_CHAIN = 2

    /** Check direct, distinct protocols first; chains are a FALLBACK only
     * after encrypted HTTPS egress tests fail. Never Cartesian-product all
     * subscriptions, and do not confuse TCP reachability with tunnel health.
     */
    fun plan(
        nodes: List<SubscriptionNode>,
        selectedId: String?,
        network: AccessSnapshot,
        supported: (SubscriptionNode) -> Boolean,
    ): LICForkBatch {
        val selected = nodes.firstOrNull { it.id == selectedId }
        val ordered = sequence {
            if (selected != null) yield(selected)
            yieldAll(nodes.asSequence().filter { it.id != selectedId })
        }.distinctBy { it.id }.take(MAX_SCAN).filter(supported).toList()
        if (ordered.isEmpty()) return LICForkBatch(emptyList(), emptyList())
        val lead = ordered.firstOrNull { it.id == selectedId }
        val sorted = ordered.filter { it.id != lead?.id }
            .sortedWith(
                compareByDescending<SubscriptionNode> {
                    LICForkLearning.quality(network, LICForkPath(it))
                }.thenBy { it.id }
            )
        val result = ArrayList<SubscriptionNode>(MAX_DIRECT)
        if (lead != null) result.add(lead)
        for (node in sorted) {
            if (result.size >= MAX_DIRECT) break
            if (result.none { it.protocol == node.protocol }) result.add(node)
        }
        for (node in sorted) {
            if (result.size >= MAX_DIRECT) break
            if (result.none { it.id == node.id }) result.add(node)
        }

        val exit = lead ?: result.first()
        // A bridge has to be a DIFFERENT endpoint. Further TLS and transport
        // checks happen in XrayConfigCompiler.compileChain.
        val bridges = ordered.asSequence()
            .filter { it.id != exit.id && it.host != exit.host }
            .filter { it.protocol.uppercase() in setOf("VLESS", "VMESS", "TROJAN") }
            .sortedByDescending { LICForkLearning.quality(network, LICForkPath(it)) }
            .take(MAX_CHAIN)
            .map { LICForkPath(exit, it) }
            .toList()
        return LICForkBatch(result.map { LICForkPath(it) }, bridges)
    }
}

/** RAM-only, per-network-class. No carrier name/SSID, IP, hostname, token,
 * user traffic, telemetry or disk persistence. Never overrides HTTPS proof.
 */
internal object LICForkLearning {
    private data class Sample(var ok: Int, var failed: Int, var latencyMs: Long, var touched: Long)
    private val samples = LinkedHashMap<String, Sample>()
    private const val MAX_ENTRIES = 96
    private const val TTL_MS = 60L * 60 * 1000

    private fun key(network: AccessSnapshot, path: LICForkPath): String =
        network.type.toString() + ":" + network.hasIpv4 + ":" +
            network.hasIpv6 + ":" + network.hasNat64 + ":" + path.key

    @Synchronized
    fun record(
        network: AccessSnapshot,
        path: LICForkPath,
        verified: Boolean,
        latencyMs: Long? = null,
        nowMs: Long = System.nanoTime() / 1_000_000L,
    ) {
        val k = key(network, path)
        val prior = samples[k]?.takeIf { nowMs - it.touched in 0..TTL_MS }
        val sample = prior ?: Sample(0, 0, 0, nowMs)
        if (verified) {
            sample.ok = (sample.ok + 1).coerceAtMost(20)
            if (latencyMs != null && latencyMs in 1..60_000)
                sample.latencyMs = if (sample.latencyMs == 0L) latencyMs
                    else (sample.latencyMs * 3 + latencyMs) / 4
        } else sample.failed = (sample.failed + 1).coerceAtMost(20)
        sample.touched = nowMs
        samples.remove(k)
        samples[k] = sample
        while (samples.size > MAX_ENTRIES) samples.remove(samples.keys.first())
    }

    @Synchronized
    fun quality(
        network: AccessSnapshot,
        path: LICForkPath,
        nowMs: Long = System.nanoTime() / 1_000_000L,
    ): Double {
        val stat = samples[key(network, path)] ?: return 0.0
        if (nowMs - stat.touched !in 0..TTL_MS) return 0.0
        val reliability = (stat.ok + 1.0) / (stat.ok + stat.failed + 3.0)
        return reliability * 100.0 -
            ((stat.latencyMs.coerceAtMost(10_000) / 1000.0) * 2.0) -
            (stat.failed * 4.0)
    }

    @Synchronized
    fun resetForTests() = samples.clear()
}
