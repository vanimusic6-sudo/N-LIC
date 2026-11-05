package io.github.romanvht.byedpi.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityComparisonTest {
    @Test fun mirroredSequenceUsesOnlyOneProviderNodeAndTwoChecksPerFingerprint() {
        val expected = listOf("profile", "firefox", "chrome", "chrome", "firefox", "profile")
        assertEquals(expected, RealityComparison.sequence)
        assertEquals(2, expected.count { it == "profile" })
        assertEquals(2, expected.count { it == "chrome" })
        assertEquals(2, expected.count { it == "firefox" })
    }

    @Test fun successfulOriginalDoesNotClaimDpiBypass() {
        val samples = RealityComparison.sequence.map {
            RealityComparison.Sample(it, true, 420L)
        }
        val result = RealityComparison.summarize(samples)
        assertTrue(result.contains("Оригинал: 2/2"))
        assertTrue(result.contains("Firefox: 2/2"))
        assertTrue(result.contains("Chrome: 2/2"))
        assertTrue(result.contains("Доказанного улучшения обхода нет"))
    }

    @Test fun alternativeWithMoreHitsIsOnlyAResearchCandidate() {
        val samples = RealityComparison.sequence.map {
            RealityComparison.Sample(it, it == "firefox", if (it == "firefox") 290L else null)
        }
        val result = RealityComparison.summarize(samples)
        assertTrue(result.contains("Оригинал: 0/2"))
        assertTrue(result.contains("Firefox: 2/2"))
        assertTrue(result.contains("Chrome: 0/2"))
        assertTrue(result.contains("Кандидат для повторной проверки: Firefox"))
        assertTrue(result.contains("не доказательство обхода DPI"))
    }

    @Test fun allFailuresAreNotMisclassifiedAsCensorship() {
        val samples = RealityComparison.sequence.map {
            RealityComparison.Sample(it, false, null)
        }
        val result = RealityComparison.summarize(samples)
        assertTrue(result.contains("Причина может быть не в DPI"))
        assertFalse(result.contains("среднее"))
    }

    @Test fun incompleteSequenceMustNotProduceARecommendation() {
        val samples = listOf(
            RealityComparison.Sample("profile", false, null),
            RealityComparison.Sample("firefox", true, 100L)
        )
        assertTrue(RealityComparison.summarize(samples).contains("Тест прерван"))
    }
}
