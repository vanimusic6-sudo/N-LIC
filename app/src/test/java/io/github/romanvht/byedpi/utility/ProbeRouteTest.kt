package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeRouteTest {
    @Test fun `zero visible networks uses testable default route with warning`() {
        assertEquals(
            ProbeRoute.SYSTEM_DEFAULT_UNBOUND,
            ProbeRoute.forBoundNetwork(false),
        )
        assertEquals(
            ProbeRoute.BOUND_PHYSICAL,
            ProbeRoute.forBoundNetwork(true),
        )
    }
}
