package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class LICAutoPolicyTest {
    private fun node(proto: String, name: String) =
        SubscriptionNode(name, name, proto, "example.org", 443, "opaque://redacted")

    @Test fun `LIC Auto never recommends a failed connection or UDP from TCP-only scan`() {
        val udp = EndpointCheck(node("HYSTERIA2", "udp"), 0, 0, null, "UDP")
        val blocked = EndpointCheck(node("VLESS", "bad"), 0, 2, null, "no route")
        assertNull(LICAuto.recommended(listOf(udp, blocked)))
    }

    @Test fun `LIC Auto requires two successful handshakes`() {
        val flaky = EndpointCheck(node("VLESS", "flaky"), 1, 2, 34, "flaky")
        val good = EndpointCheck(node("VLESS", "good"), 2, 2, 40, "good")
        assertEquals(good, LICAuto.recommended(listOf(flaky, good)))
    }

    @Test fun `LIC Bay refuses UDP-only transit`() {
        assertFalse(LICBay.plan(node("HYSTERIA2", "hy2")).canWrapTcp)
        assertFalse(LICBay.plan(node("TUIC", "tuic")).canWrapTcp)
        val vless = LICBay.plan(node("VLESS", "vless"))
        assertTrue(vless.canWrapTcp)
        assertEquals("lic-bay-local-socks", vless.outboundDetourTag)
    }
}
