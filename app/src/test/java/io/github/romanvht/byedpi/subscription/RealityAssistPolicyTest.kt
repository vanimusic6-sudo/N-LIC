package io.github.romanvht.byedpi.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val realityChrome by lazy { compile(
        "security=reality&sni=www.example.com&fp=chrome" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp"
    ) }

    @Test fun calibratedVariantsDeduplicateImportedChromeAndPreserveSameNode() {
        assertEquals("chrome", RealityAssistPolicy.baselineFingerprint(realityChrome.json))
        assertEquals(listOf<String?>(null, "firefox", "safari"),
            RealityAssistPolicy.candidates("smart", realityChrome, false))
        assertEquals(listOf<String?>(null, "firefox", "safari"),
            RealityAssistPolicy.calibrationCandidates("chrome"))
    }

    @Test fun originalFirefoxUsesChromeAndSafariAsAlternates() {
        val config = compile("security=reality&sni=www.example.com&fp=firefox" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=11&type=tcp")
        assertEquals(listOf<String?>(null, "chrome", "safari"),
            RealityAssistPolicy.candidates("smart", config, false))
    }

    @Test fun cachedVerifiedSafariIsTriedFirstButNeverChangesServer() {
        assertEquals(listOf<String?>("safari", null, "firefox"),
            RealityAssistPolicy.candidates("smart", realityChrome, false, "safari"))
        assertEquals(listOf<String?>(null, "firefox", "safari"),
            RealityAssistPolicy.candidates("smart", realityChrome, false, "edge"))
        assertEquals(listOf<String?>(null, "firefox", "safari"),
            RealityAssistPolicy.candidates("smart", realityChrome, false, "profile"))
    }

    @Test fun fastestValidatedVariantWinsOnlyWhenAdvantageIsMaterial() {
        fun m(fp: String?, latency: Long?) = RealityAssistPolicy.Measurement(fp, latency)
        assertEquals("chrome", RealityAssistPolicy.chooseBest(
            "firefox", listOf(m(null, 900), m("chrome", 680), m("safari", 700))
        ))
        assertEquals(null, RealityAssistPolicy.chooseBest(
            "firefox", listOf(m(null, 900), m("chrome", 855), m("safari", 870))
        ))
        assertEquals("safari", RealityAssistPolicy.chooseBest(
            "chrome", listOf(m(null, null), m("firefox", null), m("safari", 750))
        ))
        assertEquals(null, RealityAssistPolicy.chooseBest(
            "chrome", listOf(m(null, null), m("firefox", null), m("safari", null))
        ))
    }

    @Test fun neverTestsAnotherFingerprintForNonRealityOrMultiServerAuto() {
        val ordinaryTls = compile("security=tls&sni=www.example.com&type=tcp")
        assertNull(RealityAssistPolicy.baselineFingerprint(ordinaryTls.json))
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("smart", ordinaryTls, false))
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("smart", realityChrome, true))
    }

    @Test fun explicitUserChoiceNeverStartsUnrequestedCalibration() {
        assertEquals(listOf<String?>("firefox"),
            RealityAssistPolicy.candidates("firefox", realityChrome, false))
        assertEquals(listOf<String?>("chrome"),
            RealityAssistPolicy.candidates("chrome", realityChrome, false))
        assertEquals(listOf<String?>("safari"),
            RealityAssistPolicy.candidates("safari", realityChrome, false))
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("profile", realityChrome, false))
    }

    @Test fun invalidJsonDoesNotTriggerAnyAlternates() {
        val invalid = XrayConfigCompiler.Result.Compiled("{invalid json", "VLESS")
        assertEquals(listOf<String?>(null),
            RealityAssistPolicy.candidates("smart", invalid, false))
    }
}
