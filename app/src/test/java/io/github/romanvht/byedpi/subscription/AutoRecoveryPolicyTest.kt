package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class AutoRecoveryPolicyTest {
    @Test fun delayIsBoundedAndBacksOffWithoutBusyLoop() {
        assertEquals(1_000L, AutoRecoveryPolicy.delayMs(-1))
        assertEquals(1_000L, AutoRecoveryPolicy.delayMs(0))
        assertEquals(3_000L, AutoRecoveryPolicy.delayMs(1))
        assertEquals(8_000L, AutoRecoveryPolicy.delayMs(2))
        assertEquals(180_000L, AutoRecoveryPolicy.delayMs(99))
    }

    @Test fun boundedRandomizedBackoffForPhysicalOutages() {
        for (attempt in 0..20) {
            val base = AutoRecoveryPolicy.delayMs(attempt)
            val min = base - base / 5
            val max = minOf(180_000L, base + base / 5)
            val samples = (0L..20L).map {
                AutoRecoveryPolicy.jitteredDelayMs(attempt, it)
            }
            assertTrue(samples.all { it in min..max })
            assertEquals(samples[0], AutoRecoveryPolicy.jitteredDelayMs(attempt, 0L))
            assertTrue(samples.distinct().size > 1)
        }
        assertEquals(180_000L, AutoRecoveryPolicy.delayMs(Int.MAX_VALUE))
    }

    @Test fun onlyAuthenticatedEgressFailuresCauseAutomaticRecovery() {
        assertTrue(AutoRecoveryPolicy.retryable("AUTO_EGRESS_LOST"))
        assertTrue(AutoRecoveryPolicy.retryable("AUTO_NO_WORKING_SERVER"))
        assertTrue(AutoRecoveryPolicy.retryable("REMOTE_TLS"))
        assertFalse(AutoRecoveryPolicy.retryable("XRAY_START"))
        assertFalse(AutoRecoveryPolicy.retryable("BAY_PROFILE"))
        assertFalse(AutoRecoveryPolicy.retryable("CREDENTIAL_STORAGE"))
        assertFalse(AutoRecoveryPolicy.retryable(null))
    }
}
