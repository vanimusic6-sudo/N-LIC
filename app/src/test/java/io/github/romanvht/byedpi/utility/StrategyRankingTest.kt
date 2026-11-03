package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.SiteResult
import io.github.romanvht.byedpi.data.StrategyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyRankingTest {
    private fun entry(cmd: String, success: Int, total: Int): StrategyResult =
        StrategyResult(command = cmd, successCount = success, totalRequests = total, isCompleted = true)

    @Test fun `working baseline beats equally working costly fake profile`() {
        val items = listOf(entry("-f-1 -t8 -d1", 5, 5), entry("-An", 5, 5))
        assertEquals("-An", items.sortedWith(StrategyRanking.comparator).first().command)
    }

    @Test fun `actual availability beats low cost and partially complete profile`() {
        val working = entry("-f-1 -t8", 4, 5)
        val broken = entry("-An", 1, 5)
        assertEquals(working, listOf(broken, working).sortedWith(StrategyRanking.comparator).first())
        val unfinished = entry("-An", 5, 5).copy(isCompleted = false)
        assertEquals(working, listOf(unfinished, working).sortedWith(StrategyRanking.comparator).first())
    }

    @Test fun `latency breaks ties after proven successful low-cost strategies`() {
        val slow = entry("-s1", 3, 3).also {
            it.siteResults.add(SiteResult("example.org", 3, 3, medianSuccessLatencyMs = 550))
        }
        val fast = entry("-s3", 3, 3).also {
            it.siteResults.add(SiteResult("example.org", 3, 3, medianSuccessLatencyMs = 80))
        }
        assertEquals(fast, listOf(slow, fast).sortedWith(StrategyRanking.comparator).first())
        assertTrue(StrategyRanking.estimatedPacketCost("-f-1 -o2") > StrategyRanking.estimatedPacketCost("-r3+s"))
    }
}
