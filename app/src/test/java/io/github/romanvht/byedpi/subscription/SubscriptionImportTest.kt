package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionImportTest {
    @Test fun `parse VLESS and Trojan while never displaying passwords`() {
        val one = "vless://123e4567-e89b-12d3-a456-426614174000@vpn.example.com:443?security=reality#Vienna"
        val two = "trojan://secret@vpn2.example.org:8443#Paris"
        val nodes = SubscriptionImport.parse("$one\n$two\n# comment")
        assertEquals(2, nodes.size)
        assertEquals("Vienna", nodes[0].name)
        assertEquals("VLESS", nodes[0].protocol)
        assertEquals("Paris", nodes[1].name)
        assertFalse(nodes[1].name.contains("secret"))
        assertEquals("vpn2.example.org", nodes[1].host)
    }

    @Test fun `base64 subscription and VMess JSON import`() {
        val vmess = """{"v":"2","ps":"Server A","add":"example.net","port":"443","id":"test"}"""
        val encodedVmess = Base64.getEncoder().encodeToString(vmess.toByteArray())
        val line = "vmess://$encodedVmess"
        val input = Base64.getEncoder().encodeToString(line.toByteArray())
        val nodes = SubscriptionImport.parse(input)
        assertEquals(1, nodes.size)
        assertEquals("VMESS", nodes[0].protocol)
        assertEquals("Server A", nodes[0].name)
    }

    @Test fun `wrapped base64 text is decoded as a single subscription`() {
        val line = "trojan://password@vpn.example.org:443#London"
        val encoded = Base64.getMimeEncoder(20, "\\n".toByteArray())
            .encodeToString(line.toByteArray()).replace("\\n", "\n")
        assertEquals(1, SubscriptionImport.parse(encoded).size)
    }

    @Test fun `invalid, unsupported and excessively large inputs are rejected`() {
        assertNull(SubscriptionImport.parseNode("https://example.com"))
        assertNull(SubscriptionImport.parseNode("vless://token@example.com:0"))
        assertNull(SubscriptionImport.parseNode("trojan://secret@example.com:99999"))
        assertTrue(SubscriptionImport.parse("wireguard://example.com\nnot-config").isEmpty())
        try {
            SubscriptionImport.parse("a".repeat(512 * 1024 + 1))
            fail("Expected length rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `stable ids deduplicate identical imports but leave credentials private`() {
        val line = "vless://token@server.example:443#Node"
        assertEquals(1, SubscriptionImport.parse("$line\n$line").size)
        val a = SubscriptionImport.parseNode(line)!!
        val b = SubscriptionImport.parseNode(line.replace("token", "another"))!!
        assertNotEquals(a.id, b.id)
    }
}
