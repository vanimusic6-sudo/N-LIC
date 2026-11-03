package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class SingBoxOutboundCompilerTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"
    private val pbk = "A".repeat(43)
    private fun node(url: String) = SubscriptionImport.parseNode(url)!!

    @Test fun `reality VLESS config produces local outbound detour without leaking tunnel secrets`() {
        val n = node("vless://$uuid@vpn.example.com:443?security=reality&sni=www.example.com&pbk=$pbk&sid=0a1b&type=tcp#MyNode")
        val json = SingBoxOutboundCompiler.buildOutbounds(n, true).toString()
        assertTrue(json.contains("lic-bay-local-socks"))
        assertTrue(json.contains("127.0.0.1"))
        assertTrue(json.contains("\"public_key\""))
        assertTrue(json.contains("\"uuid\""))
        assertFalse(json.contains("MyNode"))
    }

    @Test fun `rejecting unsupported transports must never silently downgrade security`() {
        val n = node("vless://$uuid@vpn.example.com:443?security=reality&pbk=$pbk&type=ws")
        try { SingBoxOutboundCompiler.compile(n, false); fail("websocket transport unsupported") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Transport")) }
    }

    @Test fun `tls trojan config works with bay off`() {
        val n = node("trojan://password@example.com:443?security=tls&sni=hello.example#test")
        val c = SingBoxOutboundCompiler.compile(n, false).config
        assertEquals("trojan", c.get("type").asString)
        assertEquals("password", c.get("password").asString)
        assertFalse(c.has("detour"))
    }

    @Test fun `unknown fields and insecure VLESS must fail closed`() {
        val n = node("vless://$uuid@server.example:443?security=none")
        try { SingBoxOutboundCompiler.compile(n, false); fail("insecure") }
        catch (_: IllegalArgumentException) {}
        val bad = node("vless://$uuid@server.example:443?security=tls&allowInsecure=1")
        try { SingBoxOutboundCompiler.compile(bad, false); fail("insecure override") }
        catch (_: IllegalArgumentException) {}
    }
}
