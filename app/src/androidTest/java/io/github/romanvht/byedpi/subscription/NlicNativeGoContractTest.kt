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
        val candidates = nodes.map { node ->
            "FIXED-${node.protocol}" to SingBoxConfigCompiler.compile(
                node, SingBoxConfigCompiler.Bay.OFF, 1080, password)
        } + listOf(
            "AUTO" to SingBoxConfigCompiler.compileAuto(
                nodes, SingBoxConfigCompiler.Bay.OFF, 1080, password),
            "BAY" to SingBoxConfigCompiler.compile(
                nodes.first(), SingBoxConfigCompiler.Bay.ON, 1080, password)
        )
        for ((name, result) in candidates) {
            val compiled = result as? SingBoxConfigCompiler.Result.Compiled
                ?: throw AssertionError("$name: Kotlin compiler rejected a valid fixture")
            try {
                check.invoke(null, compiled.config)
            } catch (error: java.lang.reflect.InvocationTargetException) {
                // Only fixture configs are checked; no real subscription URI
                // or passwords from the user's device are involved.
                throw AssertionError("$name: native schema rejection: " +
                    error.targetException.javaClass.simpleName + ": " +
                    error.targetException.message, error)
            }
        }
    }
}
