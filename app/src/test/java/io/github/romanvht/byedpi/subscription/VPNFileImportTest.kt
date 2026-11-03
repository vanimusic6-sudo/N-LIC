package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VPNFileImportTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private fun wireguard(extra: String = "") = """
        [Interface]
        PrivateKey = $key
        Address = 10.42.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = $key
        Endpoint = vpn.example.org:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
        $extra
    """.trimIndent()

    @Test fun `WireGuard is imported as an endpoint not obsolete outbound`() {
        val nodes = VPNFileImport.parse("my-vpn.conf", wireguard())
        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals("WIREGUARD", node.protocol)
        assertEquals("vpn.example.org", node.host)
        assertEquals(51820, node.port)
        assertFalse(node.name.contains("PrivateKey"))
        val result = SingBoxConfigCompiler.compile(node,
            SingBoxConfigCompiler.Bay.OFF, 1080, "a".repeat(48))
        assertTrue(result is SingBoxConfigCompiler.Result.Compiled)
        val root = JsonParser.parseString(
            (result as SingBoxConfigCompiler.Result.Compiled).config).asJsonObject
        assertEquals("wireguard", root.getAsJsonArray("endpoints")[0]
            .asJsonObject.get("type").asString)
        assertEquals("lic-vpn", root.getAsJsonObject("route").get("final").asString)
        assertEquals("socks", root.getAsJsonArray("inbounds")[0]
            .asJsonObject.get("type").asString)
    }

    @Test fun `WireGuard rejects hooks, extra peers, missing full tunnel, weak keys`() {
        assertTrue(VPNFileImport.parse("test.conf", wireguard("PostUp = curl bad")).isEmptyOrThrows())
        assertNull(VPNFileImport.wireguard(wireguard().replace("AllowedIPs = 0.0.0.0/0, ::/0",
            "AllowedIPs = 10.0.0.0/8")))
        assertNull(VPNFileImport.wireguard(wireguard() + "\n[Peer]\nPublicKey=$key"))
        assertNull(VPNFileImport.wireguard(wireguard().replace("PrivateKey = $key",
            "PrivateKey = broken")))
        val node = VPNFileImport.parse("test.conf", wireguard()).single()
        assertTrue(SingBoxConfigCompiler.compile(node, SingBoxConfigCompiler.Bay.ON) is
            SingBoxConfigCompiler.Result.Unsupported)
    }

    @Test fun `JSON imports only secure outbounds and discards untrusted route`() {
        val json = """
          {"route":{"final":"direct"},"inbounds":[{"type":"http","listen":"0.0.0.0"}],
          "outbounds":[
            {"type":"direct","tag":"LEAK"},
            {"type":"vless","tag":"safe","server":"vpn.example.org","server_port":443,
             "uuid":"b63169b1-8e95-48f2-8c8b-431ceef44999",
             "tls":{"enabled":true,"server_name":"vpn.example.org","insecure":false}},
            {"type":"trojan","server":"bad.example.org","server_port":443,
             "password":"pass","tls":{"enabled":true,"insecure":true}}
          ]}
        """.trimIndent()
        val nodes = VPNFileImport.parse("client.json", json)
        assertEquals(1, nodes.size)
        assertEquals("VLESS", nodes.single().protocol)
        val compiled = SingBoxConfigCompiler.compile(nodes.single())
            as SingBoxConfigCompiler.Result.Compiled
        assertFalse(compiled.config.contains("LEAK"))
        assertFalse(compiled.config.contains("0.0.0.0"))
    }

    private fun List<SubscriptionNode>.isEmptyOrThrows() = isEmpty()
}
