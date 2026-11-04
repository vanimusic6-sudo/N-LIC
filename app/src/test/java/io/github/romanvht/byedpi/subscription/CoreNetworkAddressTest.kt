package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class CoreNetworkAddressTest {
    @Test fun `physical IPv6 scope must be removed before Go MustParsePrefix`() {
        assertEquals("fe80::1234/64",
            CoreNetworkAddress.prefix("fe80::1234%wlan0", 64))
        assertEquals("fe80::1234/64",
            CoreNetworkAddress.prefix("fe80::1234%7", 64))
        assertEquals("2001:db8::100/64",
            CoreNetworkAddress.prefix("2001:db8::100", 64))
        assertEquals("192.168.1.17/24",
            CoreNetworkAddress.prefix("192.168.1.17", 24))
    }

    @Test fun `never pass hostnames or invalid prefixes to native Go parser`() {
        assertNull(CoreNetworkAddress.prefix("bad.vpn.example", 24))
        assertNull(CoreNetworkAddress.prefix("wlan0", 24))
        assertNull(CoreNetworkAddress.prefix("192.168.1.256", 24))
        assertNull(CoreNetworkAddress.prefix("192.168.1.2", 64))
        assertNull(CoreNetworkAddress.prefix("fe80::abc", 129))
        assertNull(CoreNetworkAddress.prefix(null, 24))
        assertNull(CoreNetworkAddress.prefix("", 24))
    }
}
