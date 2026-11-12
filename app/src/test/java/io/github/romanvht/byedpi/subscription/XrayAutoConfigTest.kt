package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class XrayAutoConfigTest {
    private val auth = LocalSocksAuth("nlic", "a".repeat(48))
    private val uuid = "b63169b1-8e95-48f2-8c8b-431ceef44999"
    private fun node(index: Int) = requireNotNull(SubscriptionImport.parseNode(
        "vless://$uuid@edge-$index.example:443?encryption=none&security=reality" +
            "&sni=www.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&sid=11&type=tcp#Node-$index"))

    @Test fun allThreeHundredLocationsAreRoutedAndObservedWithoutDirectFallback() {
        val nodes = (1..300).map(::node)
        val result = XrayConfigCompiler.compileAuto(nodes + nodes, auth)
            as XrayConfigCompiler.Result.Compiled
        val root = JsonParser.parseString(result.json).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(300, outbounds.size())
        assertTrue(outbounds.all { it.asJsonObject.get("protocol").asString == "vless" })
        val observer = root.getAsJsonObject("observatory")
        assertTrue(observer.get("enableConcurrency").asBoolean)
        assertEquals("180s", observer.get("probeInterval").asString)
        val balancer = root.getAsJsonObject("routing").getAsJsonArray("balancers")[0].asJsonObject
        assertEquals("leastPing", balancer.getAsJsonObject("strategy").get("type").asString)
        assertEquals(outbounds[0].asJsonObject.get("tag"), balancer.get("fallbackTag"))
        val inbound = root.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals("password", inbound.getAsJsonObject("settings").get("auth").asString)
    }

    @Test fun smartAddsRealHelperVariantsAlongsideOriginalWithSameCredentials() {
        val root = JsonParser.parseString((XrayConfigCompiler.compileAuto(
            listOf(node(1)), auth, assistMode = RealityAssistPolicy.SMART)
            as XrayConfigCompiler.Result.Compiled).json).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(3, outbounds.size())
        val users = outbounds.map { it.asJsonObject.getAsJsonObject("settings")
            .getAsJsonArray("vnext")[0].asJsonObject.getAsJsonArray("users")[0].toString() }
        assertEquals(1, users.distinct().size)
        assertTrue(outbounds[0].asJsonObject.getAsJsonObject("streamSettings").has("finalmask"))
        assertFalse(outbounds[2].asJsonObject.getAsJsonObject("streamSettings").has("finalmask"))
    }

    @Test fun bayRemainsMandatoryForAllEncryptedCandidates() {
        val result = XrayConfigCompiler.compileAuto(listOf(node(1), node(2)), auth,
            XrayConfigCompiler.BAY_PORT, RealityAssistPolicy.SMART) as XrayConfigCompiler.Result.Compiled
        val outbounds = JsonParser.parseString(result.json).asJsonObject.getAsJsonArray("outbounds")
        assertEquals(3, outbounds.size())
        for (outbound in outbounds.take(2)) {
            assertEquals(XrayConfigCompiler.BAY_TAG, outbound.asJsonObject
                .getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("dialerProxy").asString)
        }
        assertEquals("socks", outbounds[2].asJsonObject.get("protocol").asString)
    }

    @Test fun unsupportedCatalogAndUntrustedHealthTargetFailClosed() {
        assertTrue(XrayConfigCompiler.compileAuto(emptyList(), auth) is XrayConfigCompiler.Result.Unsupported)
        assertTrue(XrayConfigCompiler.compileAuto(listOf(node(1)), auth,
            probeUrl = "https://untrusted.invalid/") is XrayConfigCompiler.Result.Unsupported)
    }
}
