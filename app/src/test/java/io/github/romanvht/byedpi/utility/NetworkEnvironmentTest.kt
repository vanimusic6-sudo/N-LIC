package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkEnvironmentTest {
    @Test fun `prefer validated WiFi otherwise choose usable mobile`() {
        val wifi = AccessCandidate(AccessType.WIFI, true, 1500, true, true)
        val cellular = AccessCandidate(AccessType.CELLULAR, true, 1500, true, true)
        assertEquals(AccessType.WIFI, AccessPolicy.select(listOf(cellular, wifi), wifi).type)
        val offlineWifi = wifi.copy(validated = false)
        assertEquals(AccessType.CELLULAR, AccessPolicy.select(listOf(offlineWifi, cellular)).type)
    }

    @Test fun `active mobile must win over simultaneously validated WiFi`() {
        val wifi = AccessCandidate(AccessType.WIFI, true, 1500, true, true)
        val mobile = AccessCandidate(AccessType.CELLULAR, true, 1400, false, true, true)
        assertEquals(AccessType.CELLULAR, AccessPolicy.select(listOf(wifi, mobile), mobile).type)
        // When only a VPN is the default Android may not expose its underlying
        // transport. Better return unknown than choose the wrong strategy.
        assertEquals(AccessType.UNKNOWN, AccessPolicy.select(listOf(wifi, mobile)).type)
        assertEquals(AccessType.WIFI, AccessPolicy.select(listOf(wifi, mobile), wifi).type)
    }

    @Test fun `mobile frames use bounded mtu while wifi retains known good baseline`() {
        assertEquals(8500, AccessPolicy.tunMtu(AccessSnapshot(AccessType.WIFI, 1500)))
        assertEquals(1400, AccessPolicy.tunMtu(AccessSnapshot(AccessType.CELLULAR, 1500)))
        assertEquals(1280, AccessPolicy.tunMtu(AccessSnapshot(AccessType.CELLULAR, 1320)))
        assertEquals(1400, AccessPolicy.tunMtu(AccessSnapshot(AccessType.CELLULAR, 0)))
        assertEquals(8500, AccessPolicy.tunMtu(AccessSnapshot(AccessType.CELLULAR, 1500), false))
    }

    @Test fun `never guess IPv4 support on IPv6 only mobile link`() {
        val link = AccessPolicy.select(listOf(AccessCandidate(AccessType.CELLULAR, true, 1500, false, true)))
        assertFalse(link.hasIpv4)
        assertTrue(link.hasIpv6)
    }

    @Test fun `capture cellular IPv6 without changing WiFi manual choice`() {
        val mobile6 = AccessSnapshot(AccessType.CELLULAR, 1500, false, true)
        val wifi6 = AccessSnapshot(AccessType.WIFI, 1500, true, true)
        assertTrue(AccessPolicy.shouldCaptureIpv6(false, mobile6, true))
        assertFalse(AccessPolicy.shouldCaptureIpv6(false, mobile6, false))
        assertFalse(AccessPolicy.shouldCaptureIpv6(false, wifi6, true))
        assertTrue(AccessPolicy.shouldCaptureIpv6(true, wifi6, false))
    }

    @Test fun `carrier DNS64 policy does not override custom resolvers or WiFi`() {
        val nat64 = AccessSnapshot(AccessType.CELLULAR, 1400, true, true, true)
        assertTrue(AccessPolicy.preferCarrierDnsOnNat64(nat64, true, "1.1.1.1"))
        assertFalse(AccessPolicy.preferCarrierDnsOnNat64(nat64, true, "9.9.9.9"))
        assertFalse(AccessPolicy.preferCarrierDnsOnNat64(nat64, false, "1.1.1.1"))
        assertFalse(AccessPolicy.preferCarrierDnsOnNat64(nat64.copy(type = AccessType.WIFI), true, "1.1.1.1"))
    }

    @Test fun `different radios use separate results files without identifiers`() {
        assertEquals("proxy_test_results_wifi.json", AccessPolicy.resultsFileName(AccessType.WIFI))
        assertEquals("proxy_test_results_cellular.json", AccessPolicy.resultsFileName(AccessType.CELLULAR))
    }
}
