package io.github.romanvht.byedpi.subscription

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** Independent regression tests for private-network and URL import guards. */
class SubscriptionFetcherTest {
    @Test fun `only explicit HTTPS URLs without userinfo or fragments are accepted`() {
        assertTrue(SubscriptionFetcher.validSubscriptionUrl(
            "https://subscription.example.org/api/v1/client?token=opaque"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("http://public.example.org/list"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("https://user:pass@public.example.org/"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("https://public.example.org/#secret"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("https://localhost/sub"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("https://127.0.0.1/sub"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("https://192.168.1.10/sub"))
        assertFalse(SubscriptionFetcher.validSubscriptionUrl("https://public.example.org:0/sub"))
    }

    @Test fun `CGNAT documentation benchmarking and special IPv6 DNS answers are rejected`() {
        val blocked = listOf(
            "100.64.0.1", "100.127.255.254", "198.18.0.1",
            "198.19.255.255", "198.51.100.42", "203.0.113.12",
            "192.0.2.10", "192.88.99.1", "0.1.2.3",
            "fc00::1", "fd12:3456::1", "2001:db8::1",
            "2001::1", "3fff::2"
        )
        for (ip in blocked) {
            assertFalse(ip, SubscriptionFetcher.publicAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(ip, SubscriptionFetcher.publicAddress(InetAddress.getByName(ip)))
        }
        assertFalse(SubscriptionFetcher.validSubscriptionUrl(
            "https://[fd12:3456::1]/subscription"))
        // IPv6-only mobile networks use NAT64 to reach public IPv4 hosts.
        // Permit DNS64 only for public IPv4; do not reintroduce SSRF.
        assertTrue(SubscriptionFetcher.publicAddress(
            InetAddress.getByName("64:ff9b::8.8.8.8")))
        assertFalse(SubscriptionFetcher.publicAddress(
            InetAddress.getByName("64:ff9b::10.0.0.1")))
    }

    @Test fun `an entire DNS response is rejected when any answer is private`() {
        val public = InetAddress.getByName("1.1.1.1")
        val private = InetAddress.getByName("10.0.0.1")
        val loopback = InetAddress.getByName("127.0.0.1")
        val ipv6Local = InetAddress.getByName("::1")
        assertEquals(listOf(public),
            SubscriptionFetcher.validateDnsAnswers(listOf(public)))
        for (invalid in listOf(private, loopback, ipv6Local)) {
            assertFalse(SubscriptionFetcher.publicAddress(invalid))
            assertThrows(IllegalArgumentException::class.java) {
                SubscriptionFetcher.validateDnsAnswers(listOf(public, invalid))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionFetcher.validateDnsAnswers(emptyList())
        }
    }
}
