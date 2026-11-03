package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.data.StrategyResult

/**
 * Conservative *recommendation*, not automatic replacement of the user's
 * currently working profile. Correctness is always more important than speed,
 * and equally successful profiles prefer less invasive packet manipulation.
 */
internal object StrategyRanking {
    val comparator: Comparator<StrategyResult> =
        compareByDescending<StrategyResult> { it.isCompleted }
            .thenByDescending { it.successPercentage }
            .thenByDescending { it.successCount }
            .thenBy { estimatedPacketCost(it.command) }
            .thenBy { medianSuccessLatencyMs(it) ?: Long.MAX_VALUE }

    fun estimatedPacketCost(command: String): Int {
        var cost = 0
        for (argument in shellSplit(command)) {
            when {
                argument.startsWith("-f") || argument.startsWith("--fake") -> cost += 5
                argument.startsWith("-d") || argument.startsWith("--disorder") -> cost += 4
                argument.startsWith("-o") || argument.startsWith("--oob") -> cost += 4
                argument.startsWith("-q") || argument.startsWith("--disoob") -> cost += 4
                argument.startsWith("-a") || argument.startsWith("--udp-fake") -> cost += 3
                argument.startsWith("-s") || argument.startsWith("--split") -> cost += 2
                argument.startsWith("-r") || argument.startsWith("--tlsrec") -> cost += 1
            }
        }
        return cost
    }

    fun medianSuccessLatencyMs(result: StrategyResult): Long? {
        val samples = result.siteResults.mapNotNull { it.medianSuccessLatencyMs }.sorted()
        return if (samples.isEmpty()) null else samples[(samples.size - 1) / 2]
    }
}
