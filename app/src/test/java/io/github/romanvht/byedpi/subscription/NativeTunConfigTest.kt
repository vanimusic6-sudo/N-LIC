package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NativeTunConfigTest {
    private val node = requireNotNull(SubscriptionImport.parseNode(
        "vless://b63169b1-8e95-48f2-8c8b-431ceef44999@vpn.invalid:443" +
            "?security=tls&sni=vpn.invalid#N-LIC-test"
    ))

    @Test fun `one native TUN replaces HEV while keeping encrypted readiness proxy`() {
        val raw = (SingBoxConfigCompiler.compile(node,
            localPassword = "a".repeat(48)) as
                SingBoxConfigCompiler.Result.Compiled).config
        val root = JsonParser.parseString(NativeTunConfig.compile(raw, 1400)).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds")
        assertEquals(2, inbounds.size())
        assertEquals("tun", inbounds[0].asJsonObject.get("type").asString)
        assertEquals("mixed", inbounds[0].asJsonObject.get("stack").asString)
        assertEquals(1400, inbounds[0].asJsonObject.get("mtu").asInt)
        assertEquals("socks", inbounds[1].asJsonObject.get("type").asString)
        assertEquals("lic-vpn", root.getAsJsonObject("route").get("final").asString)
        assertEquals("lic-private-doh", root.getAsJsonObject("dns").get("final").asString)
        val doh = root.getAsJsonObject("dns").getAsJsonArray("servers")[1].asJsonObject
        assertEquals("https", doh.get("type").asString)
        assertEquals("lic-vpn", doh.get("detour").asString)
        assertFalse(doh.getAsJsonObject("tls").get("insecure").asBoolean)
        assertThrows(IllegalArgumentException::class.java) {
            NativeTunConfig.compile(root.toString(), 1400)
        }
    }

    @Test fun `valid native TUN accepts UDP upstream and refuses unsafe MTU`() {
        val node2 = requireNotNull(SubscriptionImport.parseNode(
            "hysteria2://dummy@udp.invalid:443?sni=udp.invalid"
        ))
        val raw = (SingBoxConfigCompiler.compile(node2,
            SingBoxConfigCompiler.Bay.ON, 11088,
            "b".repeat(48)) as SingBoxConfigCompiler.Result.Compiled).config
        val json = JsonParser.parseString(NativeTunConfig.compile(raw, 1280)).asJsonObject
        assertEquals("hysteria2", json.getAsJsonArray("outbounds")[0].asJsonObject
            .get("type").asString)
        assertEquals("lic-bay-local-socks", json.getAsJsonArray("outbounds")[0]
            .asJsonObject.get("detour").asString)
        assertThrows(IllegalArgumentException::class.java) {
            NativeTunConfig.compile(raw, 1100)
        }
    }

    @Test fun `HTTPUpgrade and H2 preserve encrypted host and path`() {
        for ((type, expected) in listOf("httpupgrade" to "httpupgrade", "h2" to "http",
            "quic" to "quic")) {
            val node = requireNotNull(SubscriptionImport.parseNode(
                "vless://b63169b1-8e95-48f2-8c8b-431ceef44999@cdn.invalid:443?" +
                    "security=tls&sni=cdn.invalid&type=$type&path=%2Fedge&host=www.invalid"
            ))
            val raw = SingBoxConfigCompiler.compile(node,
                SingBoxConfigCompiler.Bay.ON)
                    as SingBoxConfigCompiler.Result.Compiled
            val output = JsonParser.parseString(raw.config).asJsonObject
            assertEquals(expected, output.getAsJsonArray("outbounds")[0]
                .asJsonObject.getAsJsonObject("transport").get("type").asString)
            assertEquals(if (type == "quic") BayTransport.UDP_ASSOCIATE
                else BayTransport.TCP_CONNECT, LICBay.plan(node).transport)
        }
    }
}
