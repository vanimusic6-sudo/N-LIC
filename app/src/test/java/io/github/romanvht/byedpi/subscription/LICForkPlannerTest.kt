package io.github.romanvht.byedpi.subscription

import io.github.romanvht.byedpi.utility.AccessSnapshot
import io.github.romanvht.byedpi.utility.AccessType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LICForkPlannerTest {
    private fun node(id: String, protocol: String, host: String = id + ".example.org") =
        SubscriptionNode(id, id, protocol, host, 443, "test://" + id)
    private val wifi = AccessSnapshot(AccessType.WIFI, hasIpv4 = true)
    private val mobile = AccessSnapshot(AccessType.CELLULAR, hasIpv6 = true)

    @After fun clean() = LICForkLearning.resetForTests()

    @Test fun diverseBoundedAndSelectedFirst() {
        val nodes = listOf(node("a", "VLESS"), node("b", "VLESS"),
            node("c", "TROJAN"), node("d", "VMESS"), node("e", "TROJAN"))
        val batch = LICForkPlanner.plan(nodes, "a", wifi) { true }
        assertEquals(listOf("a", "c", "d"), batch.direct.map { it.exit.id })
        assertTrue(batch.direct.all { !it.isChain })
        assertEquals(2, batch.chained.size)
        assertTrue(batch.chained.all { it.exit.id == "a" && it.bridge?.id != "a" })
    }

    @Test fun cannotChainOneNodeOrSameEndpoint() {
        assertTrue(LICForkPlanner.plan(listOf(node("a", "VLESS")), "a", wifi) { true }
            .chained.isEmpty())
        assertTrue(LICForkPlanner.plan(listOf(node("a", "VLESS", "same"),
            node("b", "TROJAN", "same")), "a", wifi) { true }.chained.isEmpty())
    }

    @Test fun noUnsupportedOrUnlimitedCandidates() {
        val nodes = (1..100).map { node("n" + it,
            if (it % 2 == 0) "VLESS" else "TROJAN") }
        var checked = 0
        val batch = LICForkPlanner.plan(nodes, null, wifi) {
            checked++
            it.id != "n1"
        }
        assertTrue(checked <= LICForkPlanner.MAX_SCAN)
        assertTrue(batch.direct.size <= LICForkPlanner.MAX_DIRECT)
        assertTrue(batch.chained.size <= LICForkPlanner.MAX_CHAIN)
        assertTrue(batch.direct.none { it.exit.id == "n1" })
    }

    @Test fun learningDoesNotBleedAcrossNetworksOrExpireIncorrectly() {
        val path = LICForkPath(node("a", "VLESS"))
        assertEquals(0.0, LICForkLearning.quality(wifi, path, 100), 0.0)
        LICForkLearning.record(wifi, path, true, 120, 200)
        assertTrue(LICForkLearning.quality(wifi, path, 201) > 0.0)
        assertEquals(0.0, LICForkLearning.quality(mobile, path, 201), 0.0)
        assertEquals(0.0, LICForkLearning.quality(wifi, path, 3_700_201), 0.0)
    }
}
