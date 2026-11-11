package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class XrayAutoSelectorTest {
    private fun node(id: String, protocol: String = "VLESS") = SubscriptionNode(
        id = id, name = id, protocol = protocol,
        host = "example.com", port = 443,
        originalUri = "vless://$id@example.com:443", group = "Test"
    )

    @Test fun chosenNodeIsTestedFirstButUnsupportedNodesAreExcluded() {
        val list = listOf(node("A"), node("B", "UNKNOWN"),
            node("C"), node("D"), node("E"))
        val chosen = XrayAutoSelector.candidates(list, "C", false) {
            it.protocol == "VLESS"
        }
        assertEquals(listOf("C", "A", "D", "E"), chosen.map { it.id })
        assertEquals(4, chosen.size)
    }

    @Test fun bestRequiresVerifiedHttpsAndRanksSmallestLatency() {
        val tested = listOf(
            XrayAutoSelector.Measurement("A", 400),
            XrayAutoSelector.Measurement("B", 250),
            XrayAutoSelector.Measurement("C", 0),
            XrayAutoSelector.Measurement("D", -1),
        )
        assertEquals("B", XrayAutoSelector.best(tested)?.id)
        assertNull(XrayAutoSelector.best(tested.drop(2)))
    }

    @Test fun unsupportedBayProtocolCannotBeChosen() {
        val list = listOf(node("A", "UNKNOWN"), node("B"))
        val chosen = XrayAutoSelector.candidates(list, "A", true) {
            it.protocol == "VLESS"
        }
        assertEquals(listOf("B"), chosen.map { it.id })
    }

    @Test fun emptyResultsNeverTriggerDirectFallback() {
        assertTrue(XrayAutoSelector.candidates(listOf(node("A")), null, false) {
            false
        }.isEmpty())
        assertNull(XrayAutoSelector.best(emptyList()))
    }
}
