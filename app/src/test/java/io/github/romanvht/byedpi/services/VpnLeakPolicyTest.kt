package io.github.romanvht.byedpi.services

import io.github.romanvht.byedpi.subscription.NativeTunConfig
import org.junit.Assert.*
import org.junit.Test

class VpnLeakPolicyTest {
    @Test fun `encrypted subscription modes always capture ipv6`() {
        assertTrue(VpnLeakPolicy.captureIpv6(true, false, false))
        assertTrue(VpnLeakPolicy.captureIpv6(false, true, false))
        assertFalse(VpnLeakPolicy.captureIpv6(false, false, false))
        assertTrue(VpnLeakPolicy.captureIpv6(false, false, true))
    }

    @Test fun `encrypted modes never inherit carrier dns`() {
        assertEquals(listOf(NativeTunConfig.IPV4_DNS),
            VpnLeakPolicy.dnsServers(true, false, "192.168.1.1"))
        assertEquals(listOf(VpnLeakPolicy.XRAY_TUNNEL_DNS_V4),
            VpnLeakPolicy.dnsServers(false, true, "192.168.1.1"))
        assertEquals(listOf("9.9.9.9"),
            VpnLeakPolicy.dnsServers(false, false, "9.9.9.9"))
        assertTrue(VpnLeakPolicy.dnsServers(false, false, "").isEmpty())
    }
}
