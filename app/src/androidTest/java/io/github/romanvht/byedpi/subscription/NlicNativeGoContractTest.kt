package io.github.romanvht.byedpi.subscription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.romanvht.byedpi.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the ACTUAL pinned Go libbox parser on Android. A JVM-only Gson unit
 * test cannot validate sing-box's native schema.
 *
 * All test credentials are intentionally fictional. These tests do not
 * connect to the Internet, read the user's vault or claim the VPN works.
 */
@RunWith(AndroidJUnit4::class)
class NlicNativeGoContractTest {
    @Test fun nativeSingBoxAcceptsCompiledFixedAndAutomaticVpnConfigurations() {
        assertTrue("This test requires the native Core Preview build",
            BuildConfig.NLIC_LIBBOX_INCLUDED)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // JNI must see a real Android DNS transport. A null platform
        // localDNSTransport silently prevents hostname-based VPN bootstrap.
        val dns = CoreLocalDnsResolver(context).transport()
        val dnsType = Class.forName("io.nekohasekai.libbox.LocalDNSTransport")
        assertTrue("Android local DNS bridge is missing", dnsType.isInstance(dns))
        assertTrue("Core bootstrap resolver must use family-aware A/AAAA queries",
            dnsType.getMethod("raw").invoke(dns) == false)
        val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
        val setupType = Class.forName("io.nekohasekai.libbox.SetupOptions")
        val setup = setupType.getConstructor().newInstance()
        val files = context.getDir("libbox-smoke", Context.MODE_PRIVATE).apply { mkdirs() }
        setupType.getMethod("setBasePath", String::class.java).invoke(setup, files.absolutePath)
        setupType.getMethod("setWorkingPath", String::class.java).invoke(setup, files.absolutePath)
        setupType.getMethod("setTempPath", String::class.java).invoke(setup, context.cacheDir.absolutePath)
        libbox.getMethod("setup", setupType).invoke(null, setup)
        val check = libbox.getMethod("checkConfig", String::class.java)

        val uuid = "b63169b1-8e95-48f2-8c8b-431ceef44999"
        val nodes = listOf(
            "vless://$uuid@test.example:443?encryption=none&security=tls&type=tcp#SMOKE-VLESS",
            "trojan://dummy-password@test.example:443?security=tls&type=tcp#SMOKE-TROJAN",
            "ss://YWVzLTI1Ni1nY206cGFzcw==@test.example:443#SMOKE-SS"
        ).mapNotNull { SubscriptionImport.parseNode(it) }
        assertTrue("Fixtures must parse", nodes.size == 3)
        val password = "a".repeat(48)
        val anytls = requireNotNull(SubscriptionImport.parseNode(
            "anytls://dummy-password@test.example:443?security=tls#SMOKE-ANYTLS"
        ))
        val wgKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val wg = VPNFileImport.parse("smoke.conf", """
            [Interface]
            PrivateKey = $wgKey
            Address = 10.99.0.2/32
            [Peer]
            PublicKey = $wgKey
            Endpoint = test.example:51820
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()).single()
        val candidates = nodes.map { node ->
            "FIXED-${node.protocol}" to SingBoxConfigCompiler.compile(
                node, SingBoxConfigCompiler.Bay.OFF, 1080, password)
        } + listOf(
            "AUTO" to SingBoxConfigCompiler.compileAuto(
                nodes, SingBoxConfigCompiler.Bay.OFF, 1080, password),
            "BAY" to SingBoxConfigCompiler.compile(
                nodes.first(), SingBoxConfigCompiler.Bay.ON, 1080, password),
            "ANYTLS" to SingBoxConfigCompiler.compile(
                anytls, SingBoxConfigCompiler.Bay.OFF, 1080, password),
            "WIREGUARD" to SingBoxConfigCompiler.compile(
                wg, SingBoxConfigCompiler.Bay.OFF, 1080, password),
            "WIREGUARD_BAY_UDP" to SingBoxConfigCompiler.compile(
                wg, SingBoxConfigCompiler.Bay.ON, 1080, password),
            "HYSTERIA2_BAY_UDP" to SingBoxConfigCompiler.compile(
                requireNotNull(SubscriptionImport.parseNode(
                    "hysteria2://pass@test.example:443?sni=test.example"
                )), SingBoxConfigCompiler.Bay.ON, 1080, password)
        )
        for ((name, result) in candidates) {
            val compiled = result as? SingBoxConfigCompiler.Result.Compiled
                ?: throw AssertionError("$name: Kotlin compiler rejected a valid fixture")
            try {
                // Validate the exact JSON passed to libbox's native
                // service, including TUN + encrypted DoH, not merely the
                // old HEV SOCKS-only compatibility profile.
                check.invoke(null, NativeTunConfig.compile(compiled.config, 1400))
            } catch (error: java.lang.reflect.InvocationTargetException) {
                // Only fixture configs are checked; no real subscription URI
                // or passwords from the user's device are involved.
                throw AssertionError("$name: native schema rejection: " +
                    error.targetException.javaClass.simpleName + ": " +
                    error.targetException.message, error)
            }
        }

        // A parsed JSON config alone does not verify that libbox can start
        // its Go runtime or accept a local SOCKS handshake. Exercise the
        // actual JNI CommandServer with a synthetic loopback-only config.
        // No real VPN server, physical tunnel or secrets are used.
        val smokePort = java.net.ServerSocket(0).use { it.localPort }
        val smokeConfig = """
            {
              "log": {"level":"warn"},
              "inbounds": [
                {"type":"socks","tag":"smoke-in","listen":"127.0.0.1",
                 "listen_port":$smokePort}
              ],
              "outbounds":[{"type":"direct","tag":"smoke-direct"}],
              "route":{"final":"smoke-direct","auto_detect_interface":false}
            }
        """.trimIndent()
        check.invoke(null, smokeConfig)
        val handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler")
        val platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        val interfacesType = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator")
        val emptyInterfaces = java.lang.reflect.Proxy.newProxyInstance(
            interfacesType.classLoader, arrayOf(interfacesType)
        ) { _, method, _ ->
            when (method.name) {
                "hasNext" -> false
                "next" -> null
                else -> null
            }
        }
        val handler = java.lang.reflect.Proxy.newProxyInstance(
            handlerType.classLoader, arrayOf(handlerType)
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        val platform = java.lang.reflect.Proxy.newProxyInstance(
            platformType.classLoader, arrayOf(platformType)
        ) { _, method, _ ->
            when (method.name) {
                "getInterfaces" -> emptyInterfaces
                "startDefaultInterfaceMonitor",
                "closeDefaultInterfaceMonitor" -> null
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }
        val serverType = Class.forName("io.nekohasekai.libbox.CommandServer")
        val server = serverType.getConstructor(handlerType, platformType)
            .newInstance(handler, platform)
        try {
            serverType.getMethod("start").invoke(server)
            val override = Class.forName("io.nekohasekai.libbox.OverrideOptions")
                .getConstructor().newInstance()
            serverType.getMethod("startOrReloadService", String::class.java,
                override.javaClass).invoke(server, smokeConfig, override)
            assertTrue("Native sing-box started but its SOCKS listener is unreachable",
                TunnelHealthProbe.checkLocal("127.0.0.1", smokePort, null))
        } finally {
            runCatching { serverType.getMethod("closeService").invoke(server) }
            runCatching { serverType.getMethod("close").invoke(server) }
        }
    }
}
