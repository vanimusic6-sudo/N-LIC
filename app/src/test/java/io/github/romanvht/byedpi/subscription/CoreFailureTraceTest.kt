package io.github.romanvht.byedpi.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFailureTraceTest {
    @Test fun controlledTimeoutsAreNotReportedAsNativeCrashes() {
        assertTrue(CoreFailureTrace.isSafeFailure("CORE_SOCKS_TIMEOUT"))
        assertTrue(CoreFailureTrace.isSafeFailure("REMOTE_TEST_TIMEOUT"))
        assertTrue(CoreFailureTrace.isSafeFailure("REMOTE_TLS"))
        assertTrue(CoreFailureTrace.isSafeFailure("REMOTE_SOCKS"))
    }

    @Test fun neverPersistArbitraryOrSensitiveErrorDetails() {
        assertFalse(CoreFailureTrace.isSafeFailure("UNKNOWN_TIMEOUT"))
        assertFalse(CoreFailureTrace.isSafeFailure("REMOTE_TLS secret=abc"))
        assertFalse(CoreFailureTrace.isSafeFailure("server.example"))
    }
}
