package io.github.romanvht.byedpi.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealityAssistPolicyTest {
    private val auth = LocalSocksAuth("nlic", "a".repeat(48))
    private val uuid = "b63169b1-8e95-48f2-8c8b-431ceef44999"

    private fun compile(fragment: String): XrayConfigCompiler.Result.Compiled {
        val node = requireNotNull(SubscriptionImport.parseNode(
            "vless://$uuid@edge.example:443?encryption=none&$fragment"
        ))
        return XrayConfigCompiler.compile(node, auth)
            as XrayConfigCompiler.Result.Compiled
    }

    @Test fun smartModeTriesSameRealityNodeWithAtMostOneAlternate() {
        val config = compile("security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp")
        assertEquals("chrome", RealityAssistPolicy.baselineFingerprint(config.json))
        assertEquals(listOf<String?>(null, "firefox"),
            RealityAssistPolicy.candidates("smart", config, false))
    }

    @Test fun firefoxOriginalUsesChromeAsAlternate() {
        val config = compile("security=reality&sni=www.example.com&fp=firefox" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp")
        assertEquals(listOf<String?>(null, "chrome"),
            RealityAssistPolicy.candidates("smart", config, false))
    }

    @Test fun neverTriesAlternatesForNonRealityOrMultiServerAuto() {
        val ordinaryTls = compile("security=tls&sni=www.example.com&type=tcp")
        assertNull(RealityAssistPolicy.baselineFingerprint(ordinaryTls.json))
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("smart", ordinaryTls, false))
        val reality = compile("security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp")
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("smart", reality, true))
    }

    @Test fun explicitUserChoiceDoesNotTriggerUnrequestedRetests() {
        val config = compile("security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp")
        assertEquals(listOf<String?>("firefox"),
            RealityAssistPolicy.candidates("firefox", config, false))
        assertEquals(listOf<String?>("chrome"),
            RealityAssistPolicy.candidates("chrome", config, false))
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("profile", config, false))
    }

    @Test fun invalidCompilerJsonNeverTriggersFallback() {
        val invalid = XrayConfigCompiler.Result.Compiled("{invalid json", "VLESS")
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("smart", invalid, false))
    }
}
