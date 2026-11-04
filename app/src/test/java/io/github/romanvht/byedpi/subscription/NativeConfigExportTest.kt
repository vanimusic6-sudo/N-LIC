package io.github.romanvht.byedpi.subscription

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Emit synthetic configs to feed into the exact sing-box 1.14.3 CLI in CI.
 * A JVM Gson test is not evidence that the Go parser accepts a config.
 *
 * NO USER SECRETS. Fixtures use reserved .invalid test names and synthetic
 * authentication keys; do not publish real subscriptions to CI artifacts.
 */
class NativeConfigExportTest {
    @Test fun exportKnownSafeNativeTunFixturesForGoParser() {
        val uuid = "b63169b1-8e95-48f2-8c8b-431ceef44999"
        val routes = linkedMapOf(
            "vless" to "vless://$uuid@vpn.invalid:443?security=tls&sni=vpn.invalid",
            "vless_reality" to ("vless://$uuid@vpn.invalid:443?security=reality" +
                "&sni=cdn.invalid&pbk=VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV"),
            "vless_ws" to "vless://$uuid@vpn.invalid:443?security=tls&sni=vpn.invalid&type=ws&path=%2Fedge",
            "vless_h2" to "vless://$uuid@vpn.invalid:443?security=tls&sni=vpn.invalid&type=h2&path=%2Fedge",
            "vless_quic" to "vless://$uuid@vpn.invalid:443?security=tls&sni=vpn.invalid&type=quic",
            "trojan" to "trojan://password@vpn.invalid:443?security=tls&sni=vpn.invalid",
            "anytls" to "anytls://password@vpn.invalid:443?security=tls&sni=vpn.invalid",
            "hysteria2" to "hysteria2://password@vpn.invalid:443?sni=vpn.invalid",
            "tuic" to "tuic://$uuid:password@vpn.invalid:443?sni=vpn.invalid",
            "shadowsocks" to "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@vpn.invalid:443",
        )
        val nodeList = routes.mapNotNull { (_, uri) -> SubscriptionImport.parseNode(uri) }
        assertTrue(nodeList.size >= 7)
        val variants = LinkedHashMap<String, SingBoxConfigCompiler.Result>()
        for ((name, uri) in routes) {
            val node = SubscriptionImport.parseNode(uri) ?: continue
            variants[name] = SingBoxConfigCompiler.compile(node)
            variants[name + "_bay"] = SingBoxConfigCompiler.compile(
                node, SingBoxConfigCompiler.Bay.ON, 11088)
        }
        variants["auto"] = SingBoxConfigCompiler.compileAuto(nodeList)
        variants["auto_bay"] = SingBoxConfigCompiler.compileAuto(
            nodeList, SingBoxConfigCompiler.Bay.ON, 11088)
        val keys = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val wg = VPNFileImport.parse("example.conf", """
            [Interface]
            PrivateKey = $keys
            Address = 10.25.0.2/32
            [Peer]
            PublicKey = $keys
            Endpoint = vpn.invalid:51820
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()).single()
        variants["wireguard"] = SingBoxConfigCompiler.compile(wg)
        variants["wireguard_bay"] = SingBoxConfigCompiler.compile(
            wg, SingBoxConfigCompiler.Bay.ON, 11088)
        val export = System.getenv("NLIC_EXPORT_NATIVE_FIXTURES") != null
        val directory = File("build/native-fixtures")
        if (export) directory.mkdirs()
        for ((name, compiled) in variants) {
            assertTrue("Kotlin rejected valid fake $name",
                compiled is SingBoxConfigCompiler.Result.Compiled)
            val conf = NativeTunConfig.compile(
                (compiled as SingBoxConfigCompiler.Result.Compiled).config, 1400)
            // Only synthetic known-safe fixture configs; no actual vault.
            if (export) File(directory, "$name.json").writeText(conf)
        }
    }
}
