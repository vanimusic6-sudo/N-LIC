package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class XrayLICForkChainTest {
    private val auth = LocalSocksAuth("nlic", "a".repeat(48))
    private fun vless(host: String, security: String = "tls"): SubscriptionNode {
        val uri = "vless://123e4567-e89b-12d3-a456-426614174000@" + host +
            ":443?security=" + security + "&type=tcp&sni=example.com"
        return requireNotNull(SubscriptionImport.parseNode(uri))
    }

    @Test fun twoEncryptedOutboundsNeverDirect() {
        val result = XrayConfigCompiler.compileChain(vless("exit.example.net"),
            vless("bridge.example.net"), auth)
        assertTrue(result is XrayConfigCompiler.Result.Compiled)
        val root = JsonParser.parseString((result as XrayConfigCompiler.Result.Compiled).json)
            .asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        assertEquals("nlic-xray", outbounds[0].asJsonObject.get("tag").asString)
        assertEquals("nlic-fork-bridge", outbounds[0].asJsonObject
            .getAsJsonObject("streamSettings").getAsJsonObject("sockopt")
            .get("dialerProxy").asString)
        assertEquals("nlic-fork-bridge", outbounds[1].asJsonObject.get("tag").asString)
        assertEquals("vless", outbounds[1].asJsonObject.get("protocol").asString)
        assertEquals("password", root.getAsJsonArray("inbounds")[0].asJsonObject
            .getAsJsonObject("settings").get("auth").asString)
    }

    @Test fun rejectsSameHostAndCleartextHop() {
        assertTrue(XrayConfigCompiler.compileChain(vless("same.example"),
            vless("same.example"), auth) is XrayConfigCompiler.Result.Unsupported)
        assertTrue(XrayConfigCompiler.compileChain(vless("exit.example"),
            vless("other.example", "none"), auth) is XrayConfigCompiler.Result.Unsupported)
    }
}
