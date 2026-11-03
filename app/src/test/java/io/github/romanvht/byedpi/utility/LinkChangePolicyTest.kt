package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkChangePolicyTest {
    @Test fun `restart only on confirmed physical network changes with explicit opt in`() {
        assertTrue(LinkChangePolicy.shouldRestart(
            AccessType.WIFI, AccessType.CELLULAR, true, true
        ))
        assertFalse(LinkChangePolicy.shouldRestart(
            AccessType.WIFI, AccessType.CELLULAR, false, true
        ))
        assertFalse(LinkChangePolicy.shouldRestart(
            AccessType.WIFI, AccessType.CELLULAR, true, false
        ))
        assertFalse(LinkChangePolicy.shouldRestart(
            AccessType.UNKNOWN, AccessType.CELLULAR, true, true
        ))
        assertFalse(LinkChangePolicy.shouldRestart(
            AccessType.WIFI, AccessType.UNKNOWN, true, true
        ))
        assertFalse(LinkChangePolicy.shouldRestart(
            AccessType.CELLULAR, AccessType.CELLULAR, true, true
        ))
    }
}
