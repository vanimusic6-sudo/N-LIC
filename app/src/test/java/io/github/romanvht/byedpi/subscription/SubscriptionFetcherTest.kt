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
