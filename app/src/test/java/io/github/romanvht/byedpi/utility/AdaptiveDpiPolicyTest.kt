package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.StrategyResult
import org.junit.Assert.*
import org.junit.Test

class AdaptiveDpiPolicyTest {
    private fun result(command: String, network: AccessType,
        good: Int, total: Int, finished: Boolean = true) =
        StrategyResult(command = command, networkType = network.name,
            successCount = good, totalRequests = total, isCompleted = finished)

    @Test fun `no measurements cannot become a claimed verified bypass`() {
        val choice = AdaptiveDpiPolicy.choose(AccessType.CELLULAR, emptyList())
        assertFalse(choice.measured)
        assertTrue(choice.command.startsWith("-"))
    }

    @Test fun `only successful tested strategies on this network are eligible`() {
        val results = listOf(
            result("-s0+sm", AccessType.WIFI, 9, 10),
            result("-r1", AccessType.CELLULAR, 2, 10),
            result("-s0+sm", AccessType.CELLULAR, 8, 10),
            result("--exec attack", AccessType.CELLULAR, 10, 10),
            result("-f9", AccessType.CELLULAR, 10, 10, finished = false)
        )
        val pick = AdaptiveDpiPolicy.choose(AccessType.CELLULAR, results)
        assertTrue(pick.measured)
        assertEquals("-s0+sm", pick.command)
        assertEquals(AccessType.CELLULAR, pick.type)
    }

    @Test fun `stable repeated results beat fragile lucky strategies`() {
        val lucky = result("-f8", AccessType.WIFI, 3, 3)
        val repeated = result("-s0+sm", AccessType.WIFI, 90, 100)
        assertTrue(AdaptiveDpiPolicy.confidenceScore(repeated) >
            AdaptiveDpiPolicy.confidenceScore(lucky))
        val choice = AdaptiveDpiPolicy.choose(AccessType.WIFI, listOf(lucky, repeated))
        assertEquals("-s0+sm", choice.command)
    }

    @Test fun `tampered benchmark commands cannot open listening ports`() {
        for (bad in listOf("-i0.0.0.0 -s0+sm", "-p8080 -s0+sm",
            "--ip=0.0.0.0 -s0+sm", "--port 8080 -s0+sm",
            "--hosts=/tmp/hosts -s0+sm", "--cache-file /tmp/cache -s0+sm")) {
            assertFalse(bad, AdaptiveDpiPolicy.isSafeCommand(bad))
        }
        assertTrue(AdaptiveDpiPolicy.isSafeCommand("-s0+sm"))
    }

    @Test fun `wrong network or insufficient successes falls back conservatively`() {
        val results = listOf(result("-f7", AccessType.WIFI, 8, 8),
            result("-r1", AccessType.ETHERNET, 2, 2))
        val pick = AdaptiveDpiPolicy.choose(AccessType.ETHERNET, results)
        assertFalse(pick.measured)
        assertNotEquals("-r1", pick.command)
    }
}
