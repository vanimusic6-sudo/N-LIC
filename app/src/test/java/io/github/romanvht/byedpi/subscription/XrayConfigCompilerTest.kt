package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class XrayConfigCompilerTest {
    private val auth = LocalSocksAuth("nlic", "a".repeat(48))
    private val uuid = "b63169b1-8e95-48f2-8c8b-431ceef44999"

    private fun node(raw: String): SubscriptionNode =
        requireNotNull(SubscriptionImport.parseNode(raw))

    private fun root(raw: String) =
        JsonParser.parseString((XrayConfigCompiler.compile(node(raw), auth)
            as XrayConfigCompiler.Result.Compiled).json).asJsonObject

    @Test fun realityUsesXraySchemaInsteadOfSingBoxFields() {
        val raw = "vless://$uuid@edge.example:443?encryption=none&security=reality" +
            "&sni=www.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&sid=11&flow=xtls-rprx-vision&type=tcp#Reality"
        val obj = root(raw)
        val inbound = obj.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("password", inbound.getAsJsonObject("settings").get("auth").asString)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        val out = obj.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("vless", out.get("protocol").asString)
        assertEquals("reality", out.getAsJsonObject("streamSettings")
            .get("security").asString)
        assertEquals("chrome", out.getAsJsonObject("streamSettings")
            .getAsJsonObject("realitySettings").get("fingerprint").asString)
        assertFalse(out.has("server_port"))
    }

    @Test fun wireguardUsesXrayUserSpaceOutbound() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val nodes = VPNFileImport.parse("wg.conf", """
            [Interface]
            PrivateKey = $key
            Address = 10.99.0.2/32
            [Peer]
            PublicKey = $key
            Endpoint = example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent())
        val compiled = XrayConfigCompiler.compile(nodes.single(), auth)
            as XrayConfigCompiler.Result.Compiled
        val out = JsonParser.parseString(compiled.json).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("wireguard", out.get("protocol").asString)
        assertTrue(out.getAsJsonObject("settings").get("noKernelTun").asBoolean)
    }

    @Test fun arbitraryAuthenticationIsRejected() {
        val raw = "vless://$uuid@test.example:443?encryption=none&security=tls&type=tcp"
        assertTrue(XrayConfigCompiler.compile(node(raw),
            LocalSocksAuth("nlic", "bad")) is XrayConfigCompiler.Result.Unsupported)
    }
}
