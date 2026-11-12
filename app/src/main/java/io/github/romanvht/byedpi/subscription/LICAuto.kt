package io.github.romanvht.byedpi.subscription

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * LIC-Auto stage 1: endpoint TCP-reachability shortlist ONLY.
 *
 * This is not a VPN performance test and must never be presented as such.
 * TLS, HTTP, jitter while streaming, UDP, and remote VLESS authentication
 * are evaluated only after a VPN-core integration exists.
 */
internal data class EndpointCheck(
    val node: SubscriptionNode,
    val successes: Int,
    val attempts: Int,
    val medianConnectMs: Long?,
    val reason: String,
) {
    val reachable: Boolean get() = successes > 0
}

internal object LICAuto {
    private const val ATTEMPTS = 2
    private const val CONNECT_TIMEOUT_MS = 1400
    private val UDP_PROTOCOLS = setOf("HYSTERIA2", "HY2", "TUIC")

    suspend fun check(nodes: List<SubscriptionNode>): List<EndpointCheck> = coroutineScope {
        val limiter = Semaphore(3)
        nodes.distinctBy { it.id }.map { node ->
            async(Dispatchers.IO) {
                limiter.withPermit {
                    coroutineContext.ensureActive()
                    if (node.protocol in UDP_PROTOCOLS) {
                        EndpointCheck(node, 0, 0, null, "UDP: needs real tunnel probe")
                    } else {
                        val samples = mutableListOf<Long>()
                        repeat(ATTEMPTS) {
                            coroutineContext.ensureActive()
                            val start = SystemClock.elapsedRealtime()
                            try {
                                Socket().use {
                                    it.connect(InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT_MS)
                                }
                                samples.add((SystemClock.elapsedRealtime() - start).coerceAtLeast(1))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // No credentials, IPs or server names in logs.
                            }
                        }
                        EndpointCheck(
                            node, samples.size, ATTEMPTS,
                            samples.sorted().let { values ->
                                if (values.isEmpty()) null else values[values.size / 2]
                            },
                            if (samples.isNotEmpty()) "TCP endpoint reachable"
                            else "TCP endpoint unavailable from this app/network",
                        )
                    }
                }
            }
        }.awaitAll().sortedWith(compareByDescending<EndpointCheck> { it.successes == ATTEMPTS }
            .thenByDescending { it.successes }
            .thenBy { it.medianConnectMs ?: Long.MAX_VALUE })
    }

    fun recommended(results: List<EndpointCheck>): EndpointCheck? =
        results.firstOrNull { it.successes == ATTEMPTS }
}
