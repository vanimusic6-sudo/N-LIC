package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.NetworkInterface
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

    @Synchronized
    fun start(config: String) {
        require(!closed.get() && server == null) { "Native core already started or stopped" }
        check(installed()) { "This APK does not contain LIC-Core native engine" }
        try {
            val setupClass = Class.forName(LIBBOX + "SetupOptions")
            val setup = setupClass.getConstructor().newInstance()
            val workspace = context.getDir("lic-core", Context.MODE_PRIVATE).apply { mkdirs() }
            workspaceDir = workspace
            // Upstream libbox writes a plaintext configuration.json snapshot
            // into basePath when starting. Isolate it into app-private storage,
            // then remove it immediately after start or on every failure.
            snapshotFile().delete()
            set(setup, "basePath", workspace.absolutePath)
            set(setup, "workingPath", workspace.absolutePath)
            set(setup, "tempPath", context.cacheDir.absolutePath)
            set(setup, "debug", false)
            set(setup, "logMaxLines", 128)
            set(setup, "appVersion", "nlic-1.9.1")
            set(setup, "appMarketingVersion", "1.9.1")
            val libbox = Class.forName(LIBBOX + "Libbox")
            libbox.getMethod("setup", setupClass).invoke(null, setup)

            val platformType = Class.forName(LIBBOX + "PlatformInterface")
            val handlerType = Class.forName(LIBBOX + "CommandServerHandler")
            val platform = Proxy.newProxyInstance(
                platformType.classLoader, arrayOf(platformType),
                PlatformHandler(context),
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
            cls.getMethod("start").invoke(instance)
            val override = Class.forName(LIBBOX + "OverrideOptions")
                .getConstructor().newInstance()
            try {
                cls.getMethod("startOrReloadService", String::class.java, override.javaClass)
                    .invoke(instance, config, override)
            } finally {
                snapshotFile().delete()
            }
        } catch (_: Throwable) {
            Log.e(TAG, "Pinned libbox native engine failed to start; no credentials are logged")
            close()
            throw IllegalStateException("LIC-Core failed to initialize or validate the selected server")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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
            snapshotFile().delete()
            server = null
            serverClass = null
        }
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

    private class PlatformHandler(private val context: Context) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> false
                "autoDetectInterfaceControl" -> Unit
                "openTun" -> throw SecurityException(
                    "Second TUN disallowed: N-LIC VpnService must own the tunnel"
                )
                "startDefaultInterfaceMonitor" -> {
                    val listener = args?.firstOrNull()
                    if (listener != null) {
                        runCatching {
                            val name = currentInterfaceName(context)
                            val iface = name?.let(NetworkInterface::getByName)
                            listener.javaClass.methods.firstOrNull {
                                it.name == "updateDefaultInterface"
                            }?.invoke(listener, name.orEmpty(), iface?.index ?: -1, false, false)
                        }
                    }
                    null
                }
                "getInterfaces" -> emptyIterator("NetworkInterfaceIterator")
                "readWIFIState", "localDNSTransport", "findConnectionOwner" -> null
                "underNetworkExtension", "includeAllNetworks" -> false
                "usePlatformShell", "usePlatformBridge", "usePlatformAutoRedirect" -> false
                "toString" -> "N-LIC Android platform"
                else -> defaultValue(method.returnType)
            }

        private fun emptyIterator(type: String): Any? {
            val clazz = runCatching { Class.forName(LIBBOX + type) }.getOrNull()
                ?: return null
            return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
                defaultValue(method.returnType)
            }
        }

        private fun currentInterfaceName(context: Context): String? {
            return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return null
            val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.activeNetwork
            } else null
            if (network == null) return null
            val caps = manager.getNetworkCapabilities(network) ?: return null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
                manager.getLinkProperties(network)?.interfaceName
            } catch (_: Exception) {
                null
            }
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
