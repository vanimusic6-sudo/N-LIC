package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class RealityWorkbenchTest {
    @Test fun allBrowserAndTransportVariantsAreVisibleOnce() {
        val rows = RealityWorkbench.profiles("chrome")
        assertEquals(10, rows.size) // profile + Firefox/Safari + seven TCP methods
        assertEquals(rows.map { it.id }.distinct(), rows.map { it.id })
        assertEquals(20, RealityWorkbench.schedule(rows).size)
        assertEquals(rows, RealityWorkbench.schedule(rows).take(rows.size))
        assertEquals(rows.asReversed(), RealityWorkbench.schedule(rows).takeLast(rows.size))
    }

    @Test fun fastestVerifiedChromeWinsEvenIfDefaultIsWorking() {
        val rows = RealityWorkbench.profiles("firefox")
        val samples = RealityWorkbench.schedule(rows).map { profile ->
            val ms = when (profile.fingerprint) {
                "chrome" -> 750L
                "safari" -> 780L
                else -> 830L
            }
            RealityWorkbench.Sample(profile.id, ms, null)
        }
        val report = RealityWorkbench.evaluate(rows, samples)
        assertTrue(report.complete)
        assertEquals("chrome", report.best!!.profile.fingerprint)
        assertEquals(750L, report.best!!.medianMs)
        assertEquals(rows.size, report.entries.count { it.good })
        val encoded = RealityWorkbench.encode(report.entries)
        val decoded = RealityWorkbench.decode(encoded, rows)!!
        assertEquals(rows.size, decoded.size)
        assertEquals("Работает", decoded.first().status)
    }

    @Test fun bestMustSucceedTwiceAndPartialDoesNotClaimWinner() {
        val rows = RealityWorkbench.profiles("firefox")
        val first = RealityWorkbench.schedule(rows).mapIndexed { i, profile ->
            val ms = if (profile.fingerprint == "chrome" && i < rows.size)
                null else if (profile.fingerprint == "chrome") 400L else 700L
            RealityWorkbench.Sample(profile.id, ms, if (ms == null) "TLS" else null)
        }
        val report = RealityWorkbench.evaluate(rows, first)
        assertTrue(report.complete)
        assertEquals("Нестабильно", report.entries.first {
            it.profile.fingerprint == "chrome"
        }.status)
        assertNotEquals("chrome", report.best?.profile?.fingerprint)
        assertNull(RealityWorkbench.evaluate(rows, first.dropLast(1)).best)
    }

    @Test fun onlyKnownValidatedProfileIdsMayBeApplied() {
        val rows = RealityWorkbench.profiles("chrome")
        val samples = RealityWorkbench.schedule(rows).map {
            RealityWorkbench.Sample(it.id, 710L, null)
        }
        val original = RealityWorkbench.encode(
            RealityWorkbench.evaluate(rows, samples).entries)
        assertNotNull(RealityWorkbench.decode(original, rows))
        assertNull(RealityWorkbench.decode(original.replace("profile~", "fake~"), rows))
        assertNull(RealityWorkbench.decode(original.replace(",2,2,", ",9,2,"), rows))
    }
}
