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

    @Test fun `LIC Bay creates a detour only for TCP outbounds`() {
        val uri = "vless://$uuid@vpn.example.net:443?security=tls&sni=cdn.example.net"
        val config = json(uri, SingBoxConfigCompiler.Bay.ON)
        val outbounds = config.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        assertEquals("lic-bay-local-socks", outbounds[0].asJsonObject.get("detour").asString)
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

    @Test fun `HY2 is a native QUIC candidate and cannot be ByeDPI wrapped`() {
        val uri = "hysteria2://secret@vpn.example:443?insecure=0&sni=cdn.example"
        assertEquals("hysteria2", json(uri).getAsJsonArray("outbounds")[0].asJsonObject
            .get("type").asString)
        assertTrue(compile(uri, SingBoxConfigCompiler.Bay.ON) is SingBoxConfigCompiler.Result.Unsupported)
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
        ).mapNotNull(SubscriptionImport::parseNode)
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

    @Test fun `LIC Bay URLTest excludes UDP first nodes and shares one local SOCKS`() {
        val nodes = listOf(
            "hysteria2://key@one.example:443?sni=one.example",
            "vless://$uuid@two.example:443?security=tls&sni=two.example",
            "trojan://pass@three.example:443?security=tls&sni=three.example",
        ).mapNotNull(SubscriptionImport::parseNode)
        val r = SingBoxConfigCompiler.compileAuto(nodes, SingBoxConfigCompiler.Bay.ON, 12080)
            as SingBoxConfigCompiler.Result.Compiled
        val json = JsonParser.parseString(r.config).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds")
        assertEquals(4, outbounds.size())
        assertEquals("socks", outbounds[2].asJsonObject.get("type").asString)
        assertEquals(12080, outbounds[2].asJsonObject.get("server_port").asInt)
        assertEquals(2, outbounds[3].asJsonObject.getAsJsonArray("outbounds").size())
        assertEquals("lic-bay-local-socks", outbounds[0].asJsonObject.get("detour").asString)
    }

    @Test fun `errors never leak the pasted secret`() {
        val result = compile("trojan://VERY_PRIVATE_PASSWORD@vpn.example:443?security=none")
        assertTrue(result is SingBoxConfigCompiler.Result.Unsupported)
        assertFalse((result as SingBoxConfigCompiler.Result.Unsupported).reason.contains("VERY_PRIVATE_PASSWORD"))
    }
}
