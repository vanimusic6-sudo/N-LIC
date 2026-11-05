package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RealityTcpTricksTest {
    private val auth = LocalSocksAuth("nlic", "a".repeat(48))
    private val uuid = "b63169b1-8e95-48f2-8c8b-431ceef44999"
    private val uri = "vless://$uuid@edge.example:443?encryption=none" +
        "&security=reality&sni=www.example.com&fp=chrome" +
        "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11" +
        "&flow=xtls-rprx-vision&type=tcp"
    private val node by lazy { requireNotNull(SubscriptionImport.parseNode(uri)) }

    private fun compile(trick: String): XrayConfigCompiler.Result.Compiled =
        XrayConfigCompiler.compile(node, auth, realityTcpTrick = trick)
            as XrayConfigCompiler.Result.Compiled

    private fun outbound(trick: String) =
        JsonParser.parseString(compile(trick).json).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject

    @Test fun splittingChangesOnlyFinalmaskAndKeepsRealityAuth() {
        val baseline = outbound(RealityTcpTricks.STOCK)
        for (trick in listOf(
            RealityTcpTricks.SPLIT_SOFT,
            RealityTcpTricks.SPLIT_EARLY,
            RealityTcpTricks.SPLIT_ZERO
        )) {
            val candidate = outbound(trick)
            val originalStream = baseline.getAsJsonObject("streamSettings")
            val stream = candidate.getAsJsonObject("streamSettings")
            val mask = stream.getAsJsonObject("finalmask")
                .getAsJsonArray("tcp")[0].asJsonObject
            assertEquals("fragment", mask.get("type").asString)
            assertEquals("tlshello", mask.getAsJsonObject("settings")
                .get("packets").asString)
            assertTrue(mask.getAsJsonObject("settings")
                .getAsJsonArray("lengths").size() >= 1)
            assertEquals(originalStream.get("realitySettings"),
                stream.get("realitySettings"))
            assertEquals(baseline.get("settings"), candidate.get("settings"))
            assertEquals("raw", stream.get("network").asString)
            assertFalse(originalStream.has("finalmask"))
        }
    }

    @Test fun mssFastOpenAndKeepAliveTouchOnlyTransportSocketOptions() {
        val stock = outbound(RealityTcpTricks.STOCK)
        val origStream = stock.getAsJsonObject("streamSettings")
        val mss1200 = outbound(RealityTcpTricks.MSS_1200)
        assertEquals(1200, mss1200.getAsJsonObject("streamSettings")
            .getAsJsonObject("sockopt").get("tcpMaxSeg").asInt)
        val mss900 = outbound(RealityTcpTricks.MSS_900)
        assertEquals(900, mss900.getAsJsonObject("streamSettings")
            .getAsJsonObject("sockopt").get("tcpMaxSeg").asInt)
        assertFalse(outbound(RealityTcpTricks.TFO_OFF)
            .getAsJsonObject("streamSettings")
            .getAsJsonObject("sockopt").get("tcpFastOpen").asBoolean)
        val idle = outbound(RealityTcpTricks.IDLE_KEEPALIVE)
            .getAsJsonObject("streamSettings").getAsJsonObject("sockopt")
        assertEquals(30, idle.get("tcpKeepAliveIdle").asInt)
        assertEquals(12, idle.get("tcpKeepAliveInterval").asInt)
        for (trick in RealityTcpTricks.variants) {
            val stream = outbound(trick).getAsJsonObject("streamSettings")
            assertEquals(origStream.get("realitySettings"),
                stream.get("realitySettings"))
        }
    }

    @Test fun noUnknownStrategyAndNoRealSocketOptionsViaByeDpiRelay() {
        assertTrue(XrayConfigCompiler.compile(
            node, auth, realityTcpTrick = "inject-fake") is
                XrayConfigCompiler.Result.Unsupported)
        assertTrue(XrayConfigCompiler.compile(
            node, auth, bayPort = XrayConfigCompiler.BAY_PORT,
            realityTcpTrick = RealityTcpTricks.SPLIT_SOFT) is
                XrayConfigCompiler.Result.Unsupported)
        assertEquals(listOf(RealityTcpTricks.STOCK), RealityTcpTricks.available(true))
    }

    @Test fun strategyDoesNotApplyToOrdinaryTls() {
        val normal = requireNotNull(SubscriptionImport.parseNode(
            "vless://$uuid@edge.example:443?encryption=none&security=tls" +
                "&sni=www.example.com&type=tcp"
        ))
        assertTrue(XrayConfigCompiler.compile(
            normal, auth, realityTcpTrick = RealityTcpTricks.MSS_1200) is
                XrayConfigCompiler.Result.Unsupported)
    }

    @Test fun fastestVerifiedTrickWinsWithoutHiddenThreshold() {
        fun m(t: String, n: Long?) = RealityTcpTricks.Measurement(t, n)
        assertEquals(RealityTcpTricks.SPLIT_SOFT, RealityTcpTricks.chooseBest(listOf(
            m("stock", 800), m("split-soft", 760), m("mss1200", 775)
        )))
        assertEquals(RealityTcpTricks.SPLIT_SOFT, RealityTcpTricks.chooseBest(listOf(
            m("stock", 900), m("split-soft", 700), m("mss1200", 810)
        )))
        assertEquals(RealityTcpTricks.SPLIT_ZERO, RealityTcpTricks.chooseBest(listOf(
            m("stock", null), m("split-soft", null), m("split-zero", 620)
        )))
        assertEquals(RealityTcpTricks.STOCK,
            RealityTcpTricks.chooseBest(listOf(m("stock", null))))
    }

    @Test fun boundedSameNodeRecoveryNeverSwitchesProtocolOrInsertsUnknownTricks() {
        val a = RealityTcpTricks.attempts("firefox", "split-soft",
            listOf("firefox", null, "chrome", "safari"), "chrome", false)
        assertEquals(RealityTcpTricks.Trial("firefox", "split-soft"), a.first())
        assertTrue(a.contains(RealityTcpTricks.Trial("firefox", "stock")))
        assertTrue(a.contains(RealityTcpTricks.Trial(null, "stock")))
        assertTrue(a.size <= 5)
        assertEquals(a.distinctBy { (it.fingerprint ?: "chrome") to it.trick }, a)
        assertFalse(a.contains(RealityTcpTricks.Trial(null, "split-soft")))
        assertEquals(listOf(RealityTcpTricks.Trial("chrome", "stock")),
            RealityTcpTricks.attempts("chrome", "split-early",
                listOf("chrome"), "chrome", true))
    }
}
