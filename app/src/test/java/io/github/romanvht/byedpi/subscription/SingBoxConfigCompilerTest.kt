package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SingBoxConfigCompilerTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    private fun compile(uri: String, bay: SingBoxConfigCompiler.Bay = SingBoxConfigCompiler.Bay.OFF): SingBoxConfigCompiler.Result =
        SingBoxConfigCompiler.compile(requireNotNull(SubscriptionImport.parseNode(uri)), bay)

    private fun json(uri: String, bay: SingBoxConfigCompiler.Bay = SingBoxConfigCompiler.Bay.OFF) =
        JsonParser.parseString(
            (compile(uri, bay) as SingBoxConfigCompiler.Result.Compiled).config
        ).asJsonObject

    @Test fun `VLESS Reality and Vision preserves essential authentication settings`() {
        val uri = "vless://$uuid@front.example:443?security=reality&sni=www.example.com" +
            "&pbk=VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV&sid=12ab" +
            "&flow=xtls-rprx-vision&fp=chrome#Example"
        val config = json(uri)
        val outbound = config.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("vless", outbound.get("type").asString)
        assertEquals(uuid, outbound.get("uuid").asString)
        assertEquals("xtls-rprx-vision", outbound.get("flow").asString)
        val tls = outbound.getAsJsonObject("tls")
        assertEquals("www.example.com", tls.get("server_name").asString)
        assertTrue(tls.getAsJsonObject("reality").get("enabled").asBoolean)
        assertFalse(tls.get("insecure").asBoolean)
        assertEquals("chrome", tls.getAsJsonObject("utls").get("fingerprint").asString)
        assertEquals("127.0.0.1", config.getAsJsonArray("inbounds")[0].asJsonObject.get("listen").asString)
        assertEquals("lic-vpn", config.getAsJsonObject("route").get("final").asString)
        assertEquals(1, config.getAsJsonArray("outbounds").size())
    }

    @Test fun `LIC Bay creates a TCP SOCKS detour for TLS VLESS outbound`() {
        val uri = "vless://$uuid@vpn.example.net:443?security=tls&sni=cdn.example.net"
        val config = json(uri, SingBoxConfigCompiler.Bay.ON)
        val outbounds = config.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        assertEquals("lic-bay-local-socks", outbounds[0].asJsonObject.get("detour").asString)
        assertEquals("vless", outbounds[0].asJsonObject.get("type").asString)
        assertEquals("socks", outbounds[1].asJsonObject.get("type").asString)
        assertEquals("127.0.0.1", outbounds[1].asJsonObject.get("server").asString)
        assertEquals(1080, outbounds[1].asJsonObject.get("server_port").asInt)
        assertEquals("lic-vpn", config.getAsJsonObject("route").get("final").asString)
    }

    @Test fun `trojan HTTPS WebSocket carries path and host without insecure downgrade`() {
        val config = json("trojan://pAss123@vpn.example:443?security=tls&sni=cdn.example&type=ws" +
            "&path=%2Fedge&host=edge.example")
        val out = config.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("pAss123", out.get("password").asString)
        assertTrue(out.getAsJsonObject("tls").get("enabled").asBoolean)
        assertEquals("ws", out.getAsJsonObject("transport").get("type").asString)
        assertEquals("/edge", out.getAsJsonObject("transport").get("path").asString)
        assertEquals("edge.example", out.getAsJsonObject("transport").getAsJsonObject("headers")
            .get("Host").asString)
    }

    @Test fun `VMess base64 imports and maps TLS field`() {
        val raw = """{"v":"2","ps":"VM","add":"vpn.example.org","port":"443",
            "id":"$uuid","aid":"0","scy":"auto","net":"ws","tls":"tls",
            "host":"origin.example","path":"/ws","sni":"tls.example"}""".trimIndent()
        val uri = "vmess://" + Base64.getEncoder().encodeToString(raw.toByteArray())
        val out = json(uri).getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("vmess", out.get("type").asString)
        assertEquals(0, out.get("alter_id").asInt)
        assertEquals("tls.example", out.getAsJsonObject("tls").get("server_name").asString)
        assertEquals("ws", out.getAsJsonObject("transport").get("type").asString)
    }

    @Test fun `Shadowsocks SIP002 credential conversion retains method and password`() {
        val raw = Base64.getEncoder().withoutPadding().encodeToString(
            "aes-256-gcm:password".toByteArray()
        )
        val out = json("ss://$raw@ss.example.net:1234#Node").getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("shadowsocks", out.get("type").asString)
        assertEquals("aes-256-gcm", out.get("method").asString)
        assertEquals("password", out.get("password").asString)
    }

    @Test fun `HY2 QUIC uses ByeDPI UDP relay with no silent direct fallback`() {
        val uri = "hysteria2://secret@vpn.example:443?insecure=0&sni=cdn.example"
        assertEquals("hysteria2", json(uri).getAsJsonArray("outbounds")[0].asJsonObject
            .get("type").asString)
        val out = json(uri, SingBoxConfigCompiler.Bay.ON).getAsJsonArray("outbounds")
        assertEquals("lic-bay-local-socks", out[0].asJsonObject.get("detour").asString)
        assertEquals("socks", out[1].asJsonObject.get("type").asString)
        assertEquals(BayTransport.UDP_ASSOCIATE,
            LICBay.plan(requireNotNull(SubscriptionImport.parseNode(uri))).transport)
    }

    @Test fun `unsupported transport and intentionally insecure TLS are rejected`() {
        assertTrue(compile("vless://$uuid@vpn.example:443?security=tls&type=xhttp") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("vless://$uuid@vpn.example:443?security=tls&allowInsecure=1") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("trojan://pass@vpn.example:443?security=none") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("vless://$uuid@vpn.example:443?security=reality&pbk=invalid") is
            SingBoxConfigCompiler.Result.Unsupported)
    }

    @Test fun `LIC Auto creates a real URLTest group without direct fallback`() {
        val nodes = listOf(
            "vless://$uuid@one.example:443?security=tls&sni=one.example",
            "trojan://secret@two.example:443?security=tls&sni=two.example",
            "hysteria2://key@three.example:443?sni=three.example",
        ).mapNotNull { SubscriptionImport.parseNode(it) }
        val r = SingBoxConfigCompiler.compileAuto(nodes)
        assertTrue(r is SingBoxConfigCompiler.Result.Compiled)
        val json = JsonParser.parseString((r as SingBoxConfigCompiler.Result.Compiled).config)
            .asJsonObject
        val outbounds = json.getAsJsonArray("outbounds")
        assertEquals(4, outbounds.size())
        assertEquals("urltest", outbounds[3].asJsonObject.get("type").asString)
        assertEquals("lic-auto", json.getAsJsonObject("route").get("final").asString)
        assertFalse(outbounds[3].asJsonObject.get("interrupt_exist_connections").asBoolean)
        assertTrue(json.getAsJsonObject("route").has("default_domain_resolver"))
    }

    @Test fun `LIC Bay URLTest includes UDP and TCP with one local SOCKS`() {
        val nodes = listOf(
            "hysteria2://key@one.example:443?sni=one.example",
            "vless://$uuid@two.example:443?security=tls&sni=two.example",
            "trojan://pass@three.example:443?security=tls&sni=three.example",
        ).mapNotNull { SubscriptionImport.parseNode(it) }
        val r = SingBoxConfigCompiler.compileAuto(nodes, SingBoxConfigCompiler.Bay.ON, 12080)
            as SingBoxConfigCompiler.Result.Compiled
        val json = JsonParser.parseString(r.config).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds")
        assertEquals(5, outbounds.size())
        assertEquals("socks", outbounds[3].asJsonObject.get("type").asString)
        assertEquals(12080, outbounds[3].asJsonObject.get("server_port").asInt)
        assertEquals(3, outbounds[4].asJsonObject.getAsJsonArray("outbounds").size())
        assertEquals("lic-bay-local-socks", outbounds[0].asJsonObject.get("detour").asString)
    }

    @Test fun `auto scans past incompatible entries before applying the candidate limit`() {
        val unsupported = (1..12).mapNotNull {
            SubscriptionImport.parseNode("vless://$uuid@bad$it.example:443?security=tls&type=xhttp")
        }
        val good = requireNotNull(SubscriptionImport.parseNode(
            "trojan://pass@good.example:443?security=tls"))
        val result = SingBoxConfigCompiler.compileAuto(unsupported + good)
        assertTrue(result is SingBoxConfigCompiler.Result.Compiled)
        val out = JsonParser.parseString((result as SingBoxConfigCompiler.Result.Compiled).config)
            .asJsonObject.getAsJsonArray("outbounds")
        assertEquals(2, out.size())
        assertEquals("good.example", out[0].asJsonObject.get("server").asString)
    }

    @Test fun `Reality supplies a TLS fingerprint when the link omits one`() {
        val out = json("vless://$uuid@vpn.example:443?security=reality&sni=cdn.example" +
            "&pbk=VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV")
            .getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("chrome", out.getAsJsonObject("tls").getAsJsonObject("utls")
            .get("fingerprint").asString)
    }

    @Test fun `unsupported VLESS encryption and incompatible Vision are not silently dropped`() {
        assertTrue(compile("vless://$uuid@vpn.example:443?encryption=unknown") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("vless://$uuid@vpn.example:443?security=tls&type=ws&flow=xtls-rprx-vision") is
            SingBoxConfigCompiler.Result.Unsupported)
    }

    @Test fun `VLESS requires authenticated TLS or Reality to protect tunnel contents`() {
        assertTrue(compile("vless://$uuid@vpn.example:443") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("vless://$uuid@vpn.example:443?security=none&type=ws") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("vless://$uuid@vpn.example:443?security=tls&sni=cdn.example") is
            SingBoxConfigCompiler.Result.Compiled)
    }

    @Test fun `alternate insecure TLS flags cannot bypass strict verification`() {
        assertTrue(compile("vless://$uuid@vpn.example:443?security=tls&allowInsecure=true") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("trojan://secret@vpn.example:443?security=tls&insecure=yes") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("hysteria2://secret@vpn.example:443?skip-cert-verify=on") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("tuic://$uuid:secret@vpn.example:443?insecure=true") is
            SingBoxConfigCompiler.Result.Unsupported)
        assertTrue(compile("trojan://secret@vpn.example:443?security=tls&insecure=0") is
            SingBoxConfigCompiler.Result.Compiled)
    }

    @Test fun `VMess unknown TLS mode cannot silently downgrade to cleartext`() {
        val base = """{"add":"vpn.example","port":"443","id":"$uuid",
            "aid":"0","scy":"auto","net":"tcp","tls":"legacy"}""".trimIndent()
        val url = "vmess://" + Base64.getEncoder().encodeToString(base.toByteArray())
        assertTrue(compile(url) is SingBoxConfigCompiler.Result.Unsupported)

        val insecure = base.replace("legacy", "tls")
            .replace("\"tls\":\"tls\"", "\"tls\":\"tls\",\"allowInsecure\":true")
        val insecureUrl = "vmess://" +
            Base64.getEncoder().encodeToString(insecure.toByteArray())
        assertTrue(compile(insecureUrl) is SingBoxConfigCompiler.Result.Unsupported)
    }

    @Test fun `private Core SOCKS listener requires an ephemeral authentication secret`() {
        val uri = "vless://$uuid@edge.example:443?security=tls"
        val node = requireNotNull(SubscriptionImport.parseNode(uri))
        val password = "a".repeat(48)
        val compiled = SingBoxConfigCompiler.compile(node,
            SingBoxConfigCompiler.Bay.OFF, 1080, password)
            as SingBoxConfigCompiler.Result.Compiled
        val json = JsonParser.parseString(compiled.config).asJsonObject
        val inbound = json.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("socks", inbound.get("type").asString)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        val account = inbound.getAsJsonArray("users")[0].asJsonObject
        assertEquals("nlic", account.get("username").asString)
        assertEquals(password, account.get("password").asString)
        val auto = SingBoxConfigCompiler.compileAuto(listOf(node),
            SingBoxConfigCompiler.Bay.OFF, 1080, password)
            as SingBoxConfigCompiler.Result.Compiled
        assertEquals(password, JsonParser.parseString(auto.config).asJsonObject
            .getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("users")[0]
            .asJsonObject.get("password").asString)
        assertTrue(SingBoxConfigCompiler.compile(node,
            SingBoxConfigCompiler.Bay.OFF, 1080, "unsafe:YAML") is
            SingBoxConfigCompiler.Result.Unsupported)
    }

    @Test fun `errors never leak the pasted secret`() {
        val result = compile("trojan://VERY_PRIVATE_PASSWORD@vpn.example:443?security=none")
        assertTrue(result is SingBoxConfigCompiler.Result.Unsupported)
        assertFalse((result as SingBoxConfigCompiler.Result.Unsupported).reason.contains("VERY_PRIVATE_PASSWORD"))
    }
}
