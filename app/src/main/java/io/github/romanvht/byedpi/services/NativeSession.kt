package io.github.romanvht.byedpi.services

import android.app.Service
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.TProxyService
import io.github.romanvht.byedpi.data.Configuration
import io.github.romanvht.byedpi.utility.AccessPolicy
import io.github.romanvht.byedpi.utility.AccessSnapshot
import io.github.romanvht.byedpi.utility.NetworkEnvironment
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getProxyIpAndPort
import io.github.romanvht.byedpi.utility.checkIpAndPortInArgs
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.subscription.SingBoxConfigCompiler
import io.github.romanvht.byedpi.subscription.LibboxRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import io.github.romanvht.byedpi.subscription.TunnelHealthProbe
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class NativeSession(
    private val service: Service,
    private val createTunnel: ((Configuration, AccessSnapshot, Int) -> ParcelFileDescriptor)? = null,
) {
    companion object {
        private const val TAG = "NativeSession"
        private const val START_TIMEOUT = 10_000L
        private const val STOP_TIMEOUT = 2_000L
        private const val PROBE_TIMEOUT = 250
        private val processStarted = AtomicBoolean()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(service.mainLooper) { message ->
        handleMessage(message)
        true
    }
    private val messenger = Messenger(handler)
    private var client: Messenger? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    private var configuration: Configuration? = null
    private var foregroundStarted = false
    private var stopOnClientDeath = false
    private var nativeStarted = false
    private var state = EngineProtocol.STATE_IDLE
    private var proxy: ByeDpiProxy? = null
    private var boxEngine: LibboxRuntime? = null
    private var proxyThread: Thread? = null
    private var tunnelThread: Thread? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var startJob: Job? = null

    val binder: IBinder get() = messenger.binder

    fun onBound(): Boolean {
        if (state == EngineProtocol.STATE_STOPPING || foregroundStarted ||
            configuration != null && !stopOnClientDeath) return false
        if (!stopOnClientDeath) {
            stopOnClientDeath = true
            handler.postDelayed({ if (configuration == null) fail("Engine configuration timed out") }, START_TIMEOUT)
        }
        return true
    }

    fun onUnbound() {
        if (stopOnClientDeath) stop()
    }

    fun onStarted() {
        if (state == EngineProtocol.STATE_STOPPING) return
        if (!foregroundStarted) {
            foregroundStarted = true
            handler.postDelayed({ if (configuration == null) stop() }, START_TIMEOUT)
        }
        startNative()
    }

    fun stop() {
        if (state == EngineProtocol.STATE_STOPPING) return
        if (!nativeStarted && processStarted.get()) {
            state = EngineProtocol.STATE_STOPPING
            service.stopSelf()
            return
        }
        state = EngineProtocol.STATE_STOPPING
        reply(EngineProtocol.STOPPING)
        handler.postDelayed({ terminate() }, STOP_TIMEOUT)
        startJob?.cancel()
        thread(name = "NativeEngineStop") {
            try {
                if (tunnelThread != null) TProxyService.stopTunnel()
                boxEngine?.close()
                proxy?.stopProxy()
                tunnelThread?.join()
                proxyThread?.join()
                tunFd?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop engine gracefully", e)
            } finally {
                terminate()
            }
        }
    }

    fun destroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        detachClient()
        if (nativeStarted || !processStarted.get()) terminate()
    }

    private fun handleMessage(message: Message) {
        when (message.what) {
            EngineProtocol.HELLO -> {
                attachClient(message.replyTo ?: return)
                reply(EngineProtocol.HELLO_ACK, Bundle().apply {
                    putInt(EngineProtocol.PID, Process.myPid())
                    putInt(EngineProtocol.STATE, state)
                })
            }
            EngineProtocol.START -> start(message.data)
            EngineProtocol.STOP -> stop()
        }
    }

    private fun attachClient(replyTo: Messenger) {
        detachClient()
        val death = IBinder.DeathRecipient {
            handler.post {
                if (client?.binder == replyTo.binder) {
                    client = null
                    clientDeath = null
                    if (stopOnClientDeath || configuration == null) stop()
                }
            }
        }
        client = replyTo
        clientDeath = death
        try {
            replyTo.binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            client = null
            clientDeath = null
            if (stopOnClientDeath || configuration == null) stop()
        }
    }

    private fun detachClient() {
        val death = clientDeath
        val endpoint = client
        client = null
        clientDeath = null
        if (death != null && endpoint != null) {
            try {
                endpoint.binder.unlinkToDeath(death, 0)
            } catch (_: NoSuchElementException) {
            }
        }
    }

    private fun start(data: Bundle) {
        if (state == EngineProtocol.STATE_STOPPING || configuration != null) return
        val args = data.getStringArray(EngineProtocol.ARGS)
        val host = data.getString(EngineProtocol.HOST)
        val port = data.getInt(EngineProtocol.PORT)
        if (args.isNullOrEmpty() || host.isNullOrBlank() || port !in 1..65535) {
            fail("Invalid engine configuration")
            return
        }
        configuration = Configuration(
            args.toList(),
            host,
            port,
            data.getString(EngineProtocol.DNS).orEmpty(),
            data.getBoolean(EngineProtocol.IPV6),
            data.getString(EngineProtocol.APP_LIST_TYPE) ?: "disable",
            data.getStringArray(EngineProtocol.APPS)?.toList().orEmpty(),
        )
        state = EngineProtocol.STATE_STARTING
        reply(EngineProtocol.CONFIGURED)
        handler.postDelayed({ if (!foregroundStarted && !stopOnClientDeath) stop() }, START_TIMEOUT)
        startNative()
    }

    private fun startNative() {
        val config = configuration ?: return
        if ((!foregroundStarted && !stopOnClientDeath) || nativeStarted || state == EngineProtocol.STATE_STOPPING) return
        if (!processStarted.compareAndSet(false, true)) {
            fail("Native session already started")
            return
        }
        nativeStarted = true
        startJob = scope.launch {
            try {
                withTimeout(50_000) {
                    if (config.args.firstOrNull() == "--nlic-libbox") {
                        check(createTunnel != null) {
                            "LIC-Core requires VPN mode; proxy-only service is unsupported"
                        }
                        val preferences = service.getPreferences()
                        val saved = withContext(Dispatchers.IO) { SubscriptionVault.read(service) }
                        val bayEnabled = config.args.getOrNull(1) == "--nlic-bay"
                        val autoEnabled = config.args.getOrNull(2) == "--nlic-auto"
                        val legacyArgs = config.args.drop(3)
                        val (legacyHostArg, legacyPortArg) = legacyArgs.checkIpAndPortInArgs()
                        val (defaultHost, defaultPort) = preferences.getProxyIpAndPort()
                        val legacyHost = legacyHostArg ?: defaultHost
                        val legacyPort = (legacyPortArg ?: defaultPort).toIntOrNull()
                            ?: error("Invalid LIC-Bay port")
                        check(!bayEnabled ||
                            legacyHost == "127.0.0.1") {
                            "LIC-Bay must listen on 127.0.0.1"
                        }
                        val bay = if (bayEnabled) SingBoxConfigCompiler.Bay.ON
                            else SingBoxConfigCompiler.Bay.OFF
                        val compiled = if (autoEnabled) {
                            SingBoxConfigCompiler.compileAuto(saved.allNodes, bay, legacyPort)
                        } else {
                            val node = saved.allNodes.firstOrNull { it.id == saved.selectedNodeId }
                                ?: error("Selected LIC-Core server is not available")
                            SingBoxConfigCompiler.compile(node, bay, legacyPort)
                        }
                        val plan = compiled as? SingBoxConfigCompiler.Result.Compiled
                            ?: error("Selected LIC-Core configuration cannot be used")
                        if (bayEnabled) {
                            check(legacyArgs.isNotEmpty()) { "Missing LIC-Bay command" }
                            val engine = withContext(Dispatchers.IO) { ByeDpiProxy() }
                            proxy = engine
                            proxyThread = runNative("ByeDPI-Bay") {
                                engine.startProxy(legacyArgs.toTypedArray())
                            }
                            withTimeout(START_TIMEOUT) { awaitProxy(legacyHost, legacyPort) }
                        }
                        val box = LibboxRuntime(service)
                        boxEngine = box
                        withContext(Dispatchers.IO) { box.start(plan.config) }
                        withTimeout(START_TIMEOUT) { awaitProxy(config.host, config.port) }
                        withTimeout(20_000) { awaitRemoteReadiness(config.host, config.port) }
                    } else {
                        val engine = withContext(Dispatchers.IO) { ByeDpiProxy() }
                        proxy = engine
                        proxyThread = runNative("ByeDPI") { engine.startProxy(config.args.toTypedArray()) }
                        withTimeout(START_TIMEOUT) { awaitProxy(config.host, config.port) }
                    }
                    createTunnel?.let { create ->
                        val underlyingLink = NetworkEnvironment.capture(service)
                        val tuneMobile = service.getPreferences().getBoolean("nlic_mobile_safe_mtu", true)
                        val mtu = AccessPolicy.tunMtu(underlyingLink, tuneMobile)
                        val fd = create(config, underlyingLink, mtu)
                        tunFd = fd
                        val tunnelConfig = buildString {
                            appendLine("tunnel:")
                            appendLine("  mtu: $mtu")
                            appendLine("misc:")
                            appendLine("  task-stack-size: 81920")
                            appendLine("socks5:")
                            appendLine("  address: ${config.host}")
                            appendLine("  port: ${config.port}")
                            appendLine("  udp: udp")
                        }
                        tunnelThread = runNative("HEV") { TProxyService.startTunnel(tunnelConfig, fd.fd) }
                        // Do not publish RUNNING when the native tunnel exited immediately.
                        delay(100)
                        check(tunnelThread?.isAlive == true) { "HEV tunnel exited during startup" }
                    }
                    if (state != EngineProtocol.STATE_STOPPING) {
                        state = EngineProtocol.STATE_RUNNING
                        reply(EngineProtocol.STARTED)
                    }
                }
            } catch (e: CancellationException) {
                if (state != EngineProtocol.STATE_STOPPING) fail("Engine startup timed out")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start engine", e)
                fail(e.message ?: "Failed to start engine")
            }
        }
    }

    private fun runNative(name: String, run: () -> Int): Thread = thread(name = name) {
        val error = try {
            "$name stopped with code ${run()}"
        } catch (e: Throwable) {
            Log.e(TAG, "$name failed", e)
            "$name: ${e.message ?: "native engine failed"}"
        }
        handler.post { if (state != EngineProtocol.STATE_STOPPING) fail(error) }
    }

    private suspend fun awaitProxy(host: String, port: Int) {
        val address = when (host) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> host
        }
        while (true) {
            val ready = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), PROBE_TIMEOUT)
                        socket.soTimeout = PROBE_TIMEOUT
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        val input = socket.getInputStream()
                        input.read() == 5 && input.read() == 0
                    }
                } catch (_: IOException) {
                    false
                }
            }
            if (ready) return
            delay(50)
        }
    }

    /** Real outbound HTTPS validation BEFORE adding Android routes. */
    private suspend fun awaitRemoteReadiness(host: String, port: Int) {
        repeat(3) { attempt ->
            val ok = withContext(Dispatchers.IO) {
                TunnelHealthProbe.check(host, port)
            }
            if (ok) return
            if (attempt < 2) delay(400)
        }
        error("LIC-Core could not validate HTTPS through the selected server")
    }

    private fun fail(error: String) {
        if (state == EngineProtocol.STATE_STOPPING) return
        Log.e(TAG, error)
        state = EngineProtocol.STATE_STOPPING
        reply(EngineProtocol.FAILED, Bundle().apply {
            putString(EngineProtocol.ERROR, error)
        })
        terminate()
    }

    private fun reply(what: Int, data: Bundle = Bundle()) {
        try {
            client?.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            client = null
            clientDeath = null
            if (stopOnClientDeath || configuration == null) stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun terminate() {
        try {
            service.stopForeground(true)
            service.stopSelf()
        } finally {
            Process.killProcess(Process.myPid())
        }
    }
}
