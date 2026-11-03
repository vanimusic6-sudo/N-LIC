package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.VpnService
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated experimental sing-box libbox service adapter.
 *
 * No native library is downloaded at runtime. The pinned AAR is included
 * ONLY in dedicated Core Preview builds. All reflection is constrained to
 * the pinned io.nekohasekai.libbox API, reducing compile-time breakage in
 * normal ByeDPI-only APKs.
 *
 * The sing-box config produced by SingBoxConfigCompiler has NO TUN inbound:
 * one Android VPN is owned by the existing N-LIC VpnService / HEV tunnel.
 */
internal class CoreStartException(val stage: String) :
    IllegalStateException("LIC-Core / $stage")

internal class LibboxRuntime(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "LICCore"
        private const val LIBBOX = "io.nekohasekai.libbox."

        fun installed(): Boolean = try {
            Class.forName(LIBBOX + "Libbox")
            Class.forName(LIBBOX + "CommandServer")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private val closed = AtomicBoolean(false)
    private var server: Any? = null
    private var workspaceDir: File? = null
    private var serverClass: Class<*>? = null
    private val networkMonitor = CoreNetworkMonitor(context)

    @Synchronized
    fun start(config: String) {
        require(!closed.get() && server == null) { "Native core already started or stopped" }
        check(installed()) { "This APK does not contain LIC-Core native engine" }
        var stage = "SETUP"
        try {
            val setupClass = Class.forName(LIBBOX + "SetupOptions")
            val setup = setupClass.getConstructor().newInstance()
            val workspace = context.getDir("lic-core", Context.MODE_PRIVATE).apply { mkdirs() }
            workspaceDir = workspace
            // Upstream libbox writes a plaintext configuration.json snapshot
            // into basePath when starting. Isolate it into app-private storage,
            // then remove it immediately after start or on every failure.
            check(eraseSnapshot()) { "Could not erase private core snapshot" }
            set(setup, "basePath", workspace.absolutePath)
            set(setup, "workingPath", workspace.absolutePath)
            set(setup, "tempPath", context.cacheDir.absolutePath)
            set(setup, "debug", false)
            set(setup, "logMaxLines", 128L)
            set(setup, "commandServerSecret", java.util.UUID.randomUUID().toString())
            set(setup, "appVersion", "nlic-1.9.4")
            set(setup, "appMarketingVersion", "1.9.4")
            val libbox = Class.forName(LIBBOX + "Libbox")
            libbox.getMethod("setup", setupClass).invoke(null, setup)

            // Validate against the *native* pinned sing-box schema before dial.
            // The full credential-bearing reflection exception is never logged.
            stage = "CONFIG"
            libbox.getMethod("checkConfig", String::class.java).invoke(null, config)

            stage = "INTERFACES"
            val platformType = Class.forName(LIBBOX + "PlatformInterface")
            val handlerType = Class.forName(LIBBOX + "CommandServerHandler")
            val platform = Proxy.newProxyInstance(
                platformType.classLoader, arrayOf(platformType),
                PlatformHandler(
                    context as? VpnService ?: error("Core must run inside Android VpnService"),
                    networkMonitor,
                ),
            )
            val handler = Proxy.newProxyInstance(
                handlerType.classLoader, arrayOf(handlerType),
                ServerHandler(),
            )
            val cls = Class.forName(LIBBOX + "CommandServer")
            val instance = cls.getConstructor(handlerType, platformType)
                .newInstance(handler, platform)
            server = instance
            serverClass = cls
            stage = "COMMAND"
            cls.getMethod("start").invoke(instance)
            val override = Class.forName(LIBBOX + "OverrideOptions")
                .getConstructor().newInstance()
            stage = "OUTBOUND"
            try {
                cls.getMethod("startOrReloadService", String::class.java, override.javaClass)
                    .invoke(instance, config, override)
            } finally {
                if (!eraseSnapshot()) {
                    stage = "CREDENTIAL_STORAGE"
                    throw CoreStartException(stage)
                }
            }
        } catch (_: Throwable) {
            Log.e(TAG, "LIC-Core startup failed at stage $stage (details redacted)")
            close()
            throw CoreStartException(stage)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        networkMonitor.close()
        eraseSnapshot()
        val instance = server ?: return
        val cls = serverClass ?: return
        try {
            cls.getMethod("closeService").invoke(instance)
        } catch (_: Throwable) {
            Log.w(TAG, "LIC-Core closeService was unsuccessful")
        }
        try {
            cls.getMethod("close").invoke(instance)
        } catch (_: Throwable) {
            Log.w(TAG, "LIC-Core close was unsuccessful")
        } finally {
            eraseSnapshot()
            server = null
            serverClass = null
        }
    }

    private fun eraseSnapshot(): Boolean {
        val file = snapshotFile()
        return !file.exists() || (file.delete() && !file.exists())
    }

    private fun snapshotFile() = File(
        requireNotNull(workspaceDir ?: context.getDir("lic-core", Context.MODE_PRIVATE)),
        "configuration.json",
    )

    private fun set(target: Any, name: String, value: Any) {
        val setter = "set" + name.replaceFirstChar { it.uppercase() }
        val method = target.javaClass.methods.firstOrNull {
            it.name == setter && it.parameterTypes.size == 1
        } ?: return // An optional setter differs between SDK revisions.
        method.invoke(target, value)
    }

    private class ServerHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "getSystemProxyStatus" -> null
                "connectSSHAgent" -> -1
                "triggerNativeCrash" -> throw SecurityException("Crash handler disabled")
                "toString" -> "N-LIC Core handler"
                else -> defaultValue(method.returnType)
            }
    }

    private class PlatformHandler(
        private val vpn: VpnService,
        private val monitor: CoreNetworkMonitor,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                // Critical: when Android installs our VPN, native sing-box
                // upstream sockets must bypass the VPN to avoid an endless
                // VPN -> SOCKS -> VPN routing loop.  Android VpnService.protect
                // is the supported, per-FD platform hook.
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.firstOrNull() as? Int)
                        ?: throw IllegalStateException("LIC-Core missing outbound file descriptor")
                    check(fd >= 0 && vpn.protect(fd)) {
                        "LIC-Core could not protect physical outbound socket"
                    }
                    null
                }
                "openTun" -> throw SecurityException(
                    "Second TUN disallowed: N-LIC VpnService must own the tunnel"
                )
                "startDefaultInterfaceMonitor" -> {
                    args?.firstOrNull()?.let(monitor::start)
                    null
                }
                "closeDefaultInterfaceMonitor" -> { monitor.close(); null }
                "getInterfaces" -> monitor.interfaces()
                "readWIFIState", "localDNSTransport", "findConnectionOwner" -> null
                "underNetworkExtension", "includeAllNetworks" -> false
                "usePlatformShell", "usePlatformBridge", "usePlatformAutoRedirect" -> false
                "toString" -> "N-LIC Android platform"
                else -> defaultValue(method.returnType)
            }


    }
}

private fun defaultValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    java.lang.String::class.java -> ""
    Void.TYPE -> null
    else -> null
}
