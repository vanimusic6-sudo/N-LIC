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

    @Test fun failedRecoveryRotatesBackupWithoutDroppingNodes() {
        val original = listOf(node("chosen"), node("reality"), node("xhttp"),
            node("other"))
        assertEquals(original, XrayAutoSelector.onRecovery(original, 0))
        assertEquals(original, XrayAutoSelector.onRecovery(original, -1))
        assertEquals(listOf("reality", "chosen", "xhttp", "other"),
            XrayAutoSelector.onRecovery(original, 1).map { it.id })
        assertEquals(listOf("xhttp", "chosen", "reality", "other"),
            XrayAutoSelector.onRecovery(original, 2).map { it.id })
        assertEquals(listOf("other", "chosen", "reality", "xhttp"),
            XrayAutoSelector.onRecovery(original, 3).map { it.id })
        assertEquals("reality", XrayAutoSelector.onRecovery(original, 4).first().id)
        for (round in 0..1000) {
            val recovered = XrayAutoSelector.onRecovery(original, round)
            assertEquals(original.map { it.id }.toSet(), recovered.map { it.id }.toSet())
            assertEquals(original.size, recovered.size)
        }
        assertEquals(original, XrayAutoSelector.onRecovery(original.take(1), 5))
        assertTrue(XrayAutoSelector.onRecovery(emptyList(), 8).isEmpty())
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
    @Test fun fullCatalogIncludesWorkingNodeBeyondEveryOldShortlist() {
        val nodes = (1..300).map { node("node-$it") }
        val selected = XrayAutoSelector.candidates(nodes, "node-250", false) { true }
        assertEquals(300, selected.size)
        assertEquals("node-250", selected.first().id)
        assertEquals(nodes.map { it.id }.toSet(), selected.map { it.id }.toSet())
    }
}
