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

    @Test fun firefoxFingerprintOverrideKeepsRealityServerAndCredentials() {
        val raw = "vless://$uuid@edge.example:443?encryption=none&security=reality" +
            "&sni=www.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&sid=11&flow=xtls-rprx-vision&type=tcp#Reality"
        val baseline = root(raw).getAsJsonArray("outbounds")[0].asJsonObject
        val result = XrayConfigCompiler.compile(
            node(raw), auth, realityClientFingerprint = "firefox"
        ) as XrayConfigCompiler.Result.Compiled
        val changed = JsonParser.parseString(result.json).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject
        val originalStream = baseline.getAsJsonObject("streamSettings")
        val newStream = changed.getAsJsonObject("streamSettings")
        assertEquals("chrome", originalStream.getAsJsonObject("realitySettings")
            .get("fingerprint").asString)
        assertEquals("firefox", newStream.getAsJsonObject("realitySettings")
            .get("fingerprint").asString)
        assertEquals(originalStream.get("network"), newStream.get("network"))
        assertEquals(originalStream.get("security"), newStream.get("security"))
        assertEquals(originalStream.getAsJsonObject("realitySettings")
            .get("password"), newStream.getAsJsonObject("realitySettings").get("password"))
        assertEquals(originalStream.getAsJsonObject("realitySettings")
            .get("shortId"), newStream.getAsJsonObject("realitySettings").get("shortId"))
        assertEquals(originalStream.getAsJsonObject("realitySettings")
            .get("serverName"), newStream.getAsJsonObject("realitySettings").get("serverName"))
        assertEquals(baseline.get("settings"), changed.get("settings"))
    }

    @Test fun utlsAssistDoesNotChangeOrdinaryTlsVless() {
        val tlsNode = node("vless://$uuid@edge.example:443?encryption=none" +
            "&security=tls&sni=www.example.com&type=tcp")
        val normal = XrayConfigCompiler.compile(tlsNode, auth)
            as XrayConfigCompiler.Result.Compiled
        val assisted = XrayConfigCompiler.compile(tlsNode, auth,
            realityClientFingerprint = "firefox") as XrayConfigCompiler.Result.Compiled
        assertEquals(JsonParser.parseString(normal.json), JsonParser.parseString(assisted.json))
    }

    @Test fun realityRejectsInvalidFingerprintAndUnsupportedWebsocket() {
        val realityNode = node("vless://$uuid@edge.example:443?encryption=none" +
            "&security=reality&sni=www.example.com&pbk=" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp")
        assertTrue(XrayConfigCompiler.compile(realityNode, auth,
            realityClientFingerprint = "randomized") is XrayConfigCompiler.Result.Unsupported)
        val ws = node("vless://$uuid@edge.example:443?encryption=none" +
            "&security=reality&sni=www.example.com&pbk=" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=ws")
        assertTrue(XrayConfigCompiler.compile(ws, auth)
            is XrayConfigCompiler.Result.Unsupported)
        val badSid = node("vless://$uuid@edge.example:443?encryption=none" +
            "&security=reality&sni=www.example.com&pbk=" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abc&type=tcp")
        assertTrue(XrayConfigCompiler.compile(badSid, auth)
            is XrayConfigCompiler.Result.Unsupported)
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

    @Test fun bayVlessUsesOnlyLoopbackSocksDialerProxy() {
        val raw = "vless://$uuid@edge.example:443?encryption=none&security=reality" +
            "&sni=www.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&sid=11&flow=xtls-rprx-vision&type=tcp#Reality"
        val compiled = XrayConfigCompiler.compile(
            node(raw), auth, XrayConfigCompiler.BAY_PORT
        ) as XrayConfigCompiler.Result.Compiled
        val root = JsonParser.parseString(compiled.json).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        val encrypted = outbounds[0].asJsonObject
        assertEquals("vless", encrypted.get("protocol").asString)
        assertEquals(XrayConfigCompiler.BAY_TAG,
            encrypted.getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt").get("dialerProxy").asString)
        val relay = outbounds[1].asJsonObject
        assertEquals("socks", relay.get("protocol").asString)
        assertEquals("127.0.0.1", relay.getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject.get("address").asString)
        assertEquals(XrayConfigCompiler.BAY_PORT, relay.getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject.get("port").asInt)
        assertFalse(relay.has("streamSettings"))
    }

    @Test fun bayWireguardHasUdpDialerProxyWithNoDirectFallback() {
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
        val result = XrayConfigCompiler.compile(
            nodes.single(), auth, XrayConfigCompiler.BAY_PORT
        ) as XrayConfigCompiler.Result.Compiled
        val outbounds = JsonParser.parseString(result.json).asJsonObject
            .getAsJsonArray("outbounds")
        assertEquals("wireguard", outbounds[0].asJsonObject.get("protocol").asString)
        assertEquals(XrayConfigCompiler.BAY_TAG, outbounds[0].asJsonObject
            .getAsJsonObject("streamSettings")
            .getAsJsonObject("sockopt").get("dialerProxy").asString)
        assertEquals("socks", outbounds[1].asJsonObject.get("protocol").asString)
    }

    @Test fun invalidBayPortFailsClosed() {
        val raw = "vless://$uuid@test.example:443?encryption=none&security=tls" +
            "&sni=example.com&type=tcp"
        assertTrue(XrayConfigCompiler.compile(node(raw), auth, -1)
            is XrayConfigCompiler.Result.Unsupported)
        assertTrue(XrayConfigCompiler.compile(node(raw), auth, XrayConfigCompiler.SOCKS_PORT)
            is XrayConfigCompiler.Result.Unsupported)
    }

    @Test fun arbitraryAuthenticationIsRejected() {
        val raw = "vless://$uuid@test.example:443?encryption=none&security=tls&type=tcp"
        assertTrue(XrayConfigCompiler.compile(node(raw),
            LocalSocksAuth("nlic", "bad")) is XrayConfigCompiler.Result.Unsupported)
    }
}
