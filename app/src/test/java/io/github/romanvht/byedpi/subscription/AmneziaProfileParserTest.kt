package io.github.romanvht.byedpi.subscription

import org.junit.Assert.*
import org.junit.Test

class AmneziaProfileParserTest {
    private val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private fun conf(extra: String = "") = """
        [Interface]
        PrivateKey = $key
        Address = 10.8.0.2/32
        DNS = 1.1.1.1
        MTU = 1350
        Jc = 3
        Jmin = 50
        Jmax = 150
        S1 = 24
        S2 = 25
        S3 = 12
        S4 = 12
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        HeaderProtectionKey = $key
        ContentPaddingAddition = 20-100
        RekeyAfterTime = 100-120
        RekeyTimeout = 5-10
        RejectAfterTime = 180-200
        KeepaliveTimeout = 8-12
        MaxHandshakeAttempts = 15-18
        RandomTrailers = on
        DisableCookies = off
        $extra

        [Peer]
        PublicKey = $key
        AllowedIPs = 0.0.0.0/0
        Endpoint = awg.example.net:55555
        PersistentKeepalive = 20
    """.trimIndent()

    @Test fun importsAwg31AsSeparateEncryptedNode() {
        val node = VPNFileImport.parse("private.conf", conf()).single()
        assertEquals("AMNEZIAWG", node.protocol)
        assertEquals("awg.example.net", node.host)
        assertTrue(node.originalUri.startsWith("awgconf://"))
        assertFalse(node.name.contains("PrivateKey"))
        val stored = VPNFileImport.readAmnezia(node)!!
        assertEquals(AmneziaProfileParser.Version.V31, stored.version)
        assertEquals(55555, stored.port)
        assertTrue(stored.rawConfig.contains("HeaderProtectionKey"))
        assertTrue(XrayConfigCompiler.compile(node, LocalSocksAuth("nlic", "a".repeat(48)))
            is XrayConfigCompiler.Result.Unsupported)
        assertTrue(SingBoxConfigCompiler.compile(node)
            is SingBoxConfigCompiler.Result.Unsupported)
    }

    @Test fun rejectsInvalidAndUnsafeAwg() {
        assertNull(AmneziaProfileParser.parse(conf().replace("S4 = 12", "S4 = 2")))
        assertNull(AmneziaProfileParser.parse(conf().replace("Jmin = 50", "Jmin = 1000")))
        assertNull(AmneziaProfileParser.parse(conf().replace("RandomTrailers = on",
            "RandomTrailers = definitely")))
        assertNull(AmneziaProfileParser.parse(conf("PostUp = curl example.com")))
        assertNull(AmneziaProfileParser.parse(conf() + "\n[Peer]\nPublicKey = $key"))
        assertNull(AmneziaProfileParser.parse(conf().replace("AllowedIPs = 0.0.0.0/0",
            "AllowedIPs = 192.168.0.0/16")))
        assertNull(AmneziaProfileParser.parse(conf().replace(
            "HeaderProtectionKey = $key", "HeaderProtectionKey = bad")))
    }

    @Test fun acceptsValidCpsAndUint32RangeFromProvider() {
        val profile = conf("I1 = <b 0xdeadbeef><r 32><t>")
            .replace("H1 = 1", "H1 = 100000-200000")
            .replace("H2 = 2", "H2 = 300000-400000")
            .replace("Jmax = 150", "Jmax = 65535")
            .replace("RekeyAfterTime = 100-120", "RekeyAfterTime = 5000-65535")
        val value = AmneziaProfileParser.parse(profile)
        assertNotNull(value)
        assertEquals(AmneziaProfileParser.Version.V31, value!!.version)
        assertTrue(value.rawConfig.contains("<b 0xdeadbeef><r 32><t>"))
    }

    @Test fun rejectsMalformedHeaderOverlapAndNumbers() {
        assertNull(AmneziaProfileParser.parse(conf()
            .replace("H1 = 1", "H1 = 1-100")
            .replace("H2 = 2", "H2 = 50-150")))
        assertNull(AmneziaProfileParser.parse(conf().replace("MTU = 1350", "MTU = nonsense")))
        assertNull(AmneziaProfileParser.parse(conf().replace("S2 = 25", "S2 = 65536")))
    }

    @Test fun plainWireGuardDoesNotBecomeAwg() {
        val normal = """
            [Interface]
            PrivateKey = $key
            Address = 10.8.0.2/32
            [Peer]
            PublicKey = $key
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.net:443
        """.trimIndent()
        assertFalse(AmneziaProfileParser.looksLikeAmnezia(normal))
        assertEquals("WIREGUARD", VPNFileImport.parse("wg.conf", normal).single().protocol)
    }

    @Test fun awgWithoutProtectionKeyIsNotMisclassifiedAs31() {
        val old = conf().lines().filterNot {
            it.contains("HeaderProtectionKey") || it.contains("ContentPaddingAddition") ||
            it.contains("RekeyAfterTime") || it.contains("RekeyTimeout") ||
            it.contains("RejectAfterTime") || it.contains("KeepaliveTimeout") ||
            it.contains("MaxHandshakeAttempts") ||
            it.contains("RandomTrailers") || it.contains("DisableCookies")
        }.joinToString("\n")
        assertEquals(AmneziaProfileParser.Version.V2,
            AmneziaProfileParser.parse(old)!!.version)
    }
}
