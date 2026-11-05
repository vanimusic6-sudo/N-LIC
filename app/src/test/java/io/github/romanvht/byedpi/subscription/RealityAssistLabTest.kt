package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class RealityAssistLabTest {
    private fun row(
        fp: String, tcp: String = "stock",
        a: Long? = 800, b: Long? = 810,
        stream: Boolean = true, failure: String = "",
    ) = RealityAssistLab.Row(fp, tcp, a, b, stream, failure)

    @Test fun workingChromeBeatsSlowerBaselineEvenBySixMilliseconds() {
        val rows = listOf(
            row("profile", a = 806, b = 810),
            row("chrome", a = 798, b = 805),
            row("firefox", a = 803, b = 809)
        )
        assertEquals("chrome", RealityAssistLab.best(rows)?.fingerprint)
        assertEquals("chrome", RealityAssistLab.bestBrowser(rows))
        assertTrue(RealityAssistLab.compact(rows).contains("Chrome"))
    }

    @Test fun noFalseWinnerWhenDownloadFailsOrOnlyOneHttpsSucceeds() {
        val rows = listOf(
            row("profile", a = 820, b = 810),
            row("chrome", a = 400, b = 405, stream = false, failure = "TIMEOUT"),
            row("firefox", a = 300, b = null)
        )
        assertEquals("profile", RealityAssistLab.best(rows)?.fingerprint)
        assertTrue(rows[1].human().contains("поток не прошёл"))
        assertTrue(rows[2].human().contains("Нестабильно"))
    }

    @Test fun fullFailuresRemainVisibleWithoutBecomingRecommendations() {
        val rows = listOf(
            row("profile", a = null, b = null, stream = false, failure = "HTTPS_TEST"),
            row("chrome", a = null, b = null, stream = false, failure = "CONFIG")
        )
        assertNull(RealityAssistLab.best(rows))
        assertTrue(RealityAssistLab.compact(rows).contains("рабочих не найдено"))
        assertTrue(rows[1].human().contains("Не поддерживается"))
    }

    @Test fun chosenTcpSplitCanWinAgainstNormalBrowserModes() {
        val rows = listOf(
            row("profile", a = 790, b = 810),
            row("chrome", a = 775, b = 780),
            row("chrome", "split-soft", 690, 710)
        )
        val best = RealityAssistLab.best(rows)
        assertEquals("chrome", best?.fingerprint)
        assertEquals("split-soft", best?.trick)
        assertEquals(700L, best?.medianMs)
        assertTrue(RealityAssistLab.explanation(rows).contains("разделение ClientHello"))
    }

    @Test fun duplicatesInImportedFingerprintAreTestedOnce() {
        assertEquals(listOf("profile", "firefox", "safari"),
            RealityAssistLab.candidates("chrome"))
        assertEquals(listOf("profile", "chrome", "safari"),
            RealityAssistLab.candidates("firefox"))
        assertEquals(listOf("profile", "chrome", "firefox", "safari"),
            RealityAssistLab.candidates("edge"))
    }

    @Test fun safeIpcSerializationCannotSmuggleUnknownStrategiesOrSecrets() {
        val good = row("firefox", "mss1200", 735, 742)
        assertEquals(good, RealityAssistLab.parse(good.encode()))
        assertNull(RealityAssistLab.parse("chrome|inject|200|200|1|"))
        assertNull(RealityAssistLab.parse("chrome|stock|200|200|1|server.example.com"))
        assertNull(RealityAssistLab.parse("chrome|stock|-8|200|1|"))
    }

    @Test fun tieUsesStockButNotArbitraryBias() {
        val rows = listOf(
            row("chrome", "split-soft", 795, 805),
            row("chrome", "stock", 795, 805)
        )
        assertEquals("stock", RealityAssistLab.best(rows)?.trick)
    }
}
