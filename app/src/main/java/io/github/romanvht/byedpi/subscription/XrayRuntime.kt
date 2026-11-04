package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds the pinned AndroidLibXrayLite v26.10.31 AAR only in Xray preview.
 * No raw configuration or nested Go exception is ever logged.
 * Xray owns local SOCKS; HEV owns Android VpnService's sole TUN.
 */
internal class XrayRuntime(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "NLICXray"
        private const val PREFIX = "libv2ray."
        fun installed(): Boolean = runCatching {
            Class.forName(PREFIX + "Libv2ray")
            Class.forName(PREFIX + "CoreController")
            true
        }.getOrDefault(false)
    }

    private var controller: Any? = null
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun start(config: String) {
        check(!closed.get() && controller == null)
        check(installed()) { "Xray core is not bundled in this APK" }
        CoreFailureTrace.mark(context, "XRAY_START")
        try {
            val module = Class.forName(PREFIX + "Libv2ray")
            val callback = Class.forName(PREFIX + "CoreCallbackHandler")
            val callbackInstance = Proxy.newProxyInstance(
                callback.classLoader, arrayOf(callback), Callback())
            // Current libv2ray Go wrapper provides a Java static API with
            // newCoreController; classes are kept by ProGuard in Xray builds.
            val controllerInstance = module.getMethod("newCoreController", callback)
                .invoke(null, callbackInstance)
            controller = controllerInstance
            // Environment path is private and does not contain user configs.
            module.getMethod("initCoreEnv", String::class.java, String::class.java)
                .invoke(null, context.filesDir.absolutePath, "")
            controllerInstance.javaClass.getMethod("startLoop", String::class.java,
                Int::class.javaPrimitiveType).invoke(controllerInstance, config, 0)
        } catch (_: Throwable) {
            Log.e(TAG, "Xray startup failed (details redacted)")
            close()
            throw CoreStartException("XRAY_START")
        }
    }

    /**
     * A second, independent HTTPS verification using Xray-core's own
     * authenticated outbound dialer (core.Dial), rather than Java's
     * SOCKS + JSSE stack. The pinned AndroidLibXrayLite measureDelay uses
     * Go's certificate-validating HTTP transport and accepts only 200/204.
     * It cannot silently fall back to a direct Android socket.
     *
     * On failure the normal SOCKS/JSSE probe still runs and reports a
     * credential-free diagnostic phase. Never interpret mere core start
     * or SOCKS CONNECT as proof that encrypted egress works.
     */
    internal fun verifyHttpsViaCore(): Boolean {
        val active = controller ?: return false
        if (closed.get()) return false
        return runCatching {
            val ms = active.javaClass
                .getMethod("measureDelay", String::class.java)
                .invoke(active, "https://example.com/") as? Number
            ms != null && ms.toLong() >= 0L
        }.getOrDefault(false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        controller?.let { instance ->
            runCatching { instance.javaClass.getMethod("stopLoop").invoke(instance) }
        }
        controller = null
    }

    private class Callback : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "startup", "shutdown", "onEmitStatus" -> 0L
                "toString" -> "N-LIC Xray callback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> when (method.returnType) {
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
    }
}
