package io.github.romanvht.byedpi.services

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.romanvht.byedpi.core.ByeDpiProxyCmdPreferences
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.core.ByeDpiProxyUIPreferences
import io.github.romanvht.byedpi.data.Configuration
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PrivateDnsState
import io.github.romanvht.byedpi.data.START_ACTION
import io.github.romanvht.byedpi.utility.*
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.subscription.SingBoxConfigCompiler
import io.github.romanvht.byedpi.subscription.CoreStartException

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal class NativeEngine(
    context: Context,
    private val mode: Mode = Mode.Proxy,
    initialPid: Int = 0,
    private val foreground: Boolean = true,
) {
    companion object {
        private const val TAG = "NativeEngine"

        fun configuration(context: Context): Configuration {
            val preferences = context.getPreferences()

            // LIC-Core is now a standalone, explicit VPN runtime.
            // Neither normal VPN nor LIC-Bay may parse legacy UI flags/ports:
            // those often contain stale values and previously caused a global
            // ENGINE failure before the native core even initialized.
            val isCore = preferences.getBoolean("nlic_core_mode", false) &&
                preferences.getStringNotNull("byedpi_mode", "vpn") == "vpn"
            if (isCore && BuildConfig.NLIC_XRAY_INCLUDED) {
                val catalog = runCatching { SubscriptionVault.read(context) }
                    .getOrElse { throw CoreStartException("CATALOG") }
                val auto = preferences.getBoolean("nlic_core_auto", false)
                val bay = preferences.getBoolean("nlic_core_bay", false)
                val bayPort = io.github.romanvht.byedpi.subscription.XrayConfigCompiler.BAY_PORT
                val authForValidation =
                    io.github.romanvht.byedpi.subscription.LocalSocksAuth("nlic", "a".repeat(48))
                val supports: (io.github.romanvht.byedpi.subscription.SubscriptionNode) -> Boolean =
                    { node ->
                        io.github.romanvht.byedpi.subscription.XrayConfigCompiler.compile(
                            node, authForValidation, if (bay) bayPort else null
                        ) is io.github.romanvht.byedpi.subscription.XrayConfigCompiler.Result.Compiled
                    }
                val eligible = if (auto) {
                    io.github.romanvht.byedpi.subscription.XrayAutoSelector.candidates(
                        catalog.allNodes, catalog.selectedNodeId, bay, supports
                    )
                } else {
                    catalog.allNodes.filter {
                        it.id == catalog.selectedNodeId && supports(it)
                    }
                }
                if (eligible.isEmpty()) throw CoreStartException("PROTOCOL_UNSUPPORTED")
                val bayArgs = if (bay) {
                    // Only the pinned local ByeDPI command generator is allowed.
                    // User subscription entries cannot add proxy flags or
                    // create an externally listening ByeDPI server.
                    val strategy = AdaptiveDpiPolicy.decide(
                        context,
                        preferences.getBoolean(AdaptiveDpiPolicy.KEY, true),
                        "-s0+sm",
                    )?.command ?: "-s0+sm"
                    val arguments = try {
                        ByeDpiProxyCmdPreferences(
                            context, strategy, "127.0.0.1", bayPort,
                        ).args.drop(1)
                    } catch (_: Exception) {
                        throw CoreStartException("BAY_PROFILE")
                    }
                    if (arguments.isEmpty() || arguments.any {
                            it in setOf("-U", "--no-udp", "--transparent", "-E")
                        }) throw CoreStartException("BAY_PROFILE")
                    arguments
                } else emptyList()
                return Configuration(
                    args = listOf(
                        "--nlic-xray",
                        if (bay) "--nlic-bay" else "--nlic-direct",
                        if (auto) "--nlic-auto" else "--nlic-fixed",
                    ) + bayArgs,
                    host = "127.0.0.1",
                    port = io.github.romanvht.byedpi.subscription.XrayConfigCompiler.SOCKS_PORT,
                    dns = preferences.getStringNotNull("dns_ip", "1.1.1.1")
                        .ifBlank { "1.1.1.1" },
                    ipv6 = true,
                    appListType = preferences.getStringNotNull("applist_type", "disable"),
                    apps = preferences.getSelectedApps(),
                )
            }
            if (isCore) {
                check(BuildConfig.NLIC_LIBBOX_INCLUDED) {
                    "Core preview runtime not bundled"
                }
                val catalog = try {
                    SubscriptionVault.read(context)
                } catch (_: Exception) {
                    // No user secrets or Java exception messages in logs/UI.
                    throw CoreStartException("CATALOG")
                }
                val auto = preferences.getBoolean("nlic_core_auto", false)
                val bay = preferences.getBoolean("nlic_core_bay", false)
                val bayPort = 11088 // Dedicated loopback port, never a public listener.
                val bayMode = if (bay) SingBoxConfigCompiler.Bay.ON
                    else SingBoxConfigCompiler.Bay.OFF
                val chosen = if (auto) {
                    SingBoxConfigCompiler.compileAuto(catalog.allNodes, bayMode, bayPort)
                } else {
                    val node = catalog.allNodes.firstOrNull {
                        it.id == catalog.selectedNodeId
                    } ?: error("Select a VPN server first")
                    SingBoxConfigCompiler.compile(node, bayMode, bayPort)
                }
                if (chosen !is SingBoxConfigCompiler.Result.Compiled) {
                    throw CoreStartException("PROTOCOL_UNSUPPORTED")
                }
                val bayArgs = if (bay) {
                    val strategy = AdaptiveDpiPolicy.decide(
                        context,
                        preferences.getBoolean(AdaptiveDpiPolicy.KEY, true),
                        "-s0+sm",
                    )?.command ?: "-s0+sm"
                    // All selected ByeDPI strategies are local. No arguments
                    // from an imported config can change the listener or run
                    // external commands. ByeDPI's SOCKS5 UDP is enabled by
                    // default; there is no --no-udp in this dedicated profile.
                    val proxy = try {
                        ByeDpiProxyCmdPreferences(
                            context, strategy, "127.0.0.1", bayPort,
                        )
                    } catch (_: Exception) {
                        throw CoreStartException("BAY_PROFILE")
                    }
                    proxy.args.drop(1).also {
                        check(it.none { option ->
                            option == "-U" || option == "--no-udp" ||
                                option == "--transparent" || option == "-E"
                        }) { "LIC-Bay requires SOCKS5 UDP and loopback mode" }
                    }
                } else emptyList()
                return Configuration(
                    args = listOf(
                        "--nlic-libbox",
                        if (bay) "--nlic-bay" else "--nlic-direct",
                        if (auto) "--nlic-auto" else "--nlic-fixed",
                    ) + bayArgs,
                    host = "127.0.0.1",
                    port = SingBoxConfigCompiler.PROXY_PORT,
                    dns = preferences.getStringNotNull("dns_ip", "1.1.1.1")
                        .ifBlank { "1.1.1.1" },
                    ipv6 = true,
                    appListType = preferences.getStringNotNull("applist_type", "disable"),
                    apps = preferences.getSelectedApps(),
                )
            }

            val manual = preferences.getCmdArgs()
            // Pick from recent, locally measured link-type outcomes.
            // Do not make up carrier-specific universal bypass flags.
            val adaptive = AdaptiveDpiPolicy.decide(context,
                preferences.getBoolean(AdaptiveDpiPolicy.KEY, true), manual)
            val selected = NetworkProfileStore.choose(
                preferences.getBoolean(NetworkProfileStore.ENABLED_KEY, false),
                NetworkEnvironment.capture(context).type,
                adaptive?.command ?: manual,
            ) { key -> preferences.getString(key, null) }
            val proxy = if ((preferences.getCmdEnable() || adaptive != null) &&
                selected != manual) {
                // Apply only on engine startup; never interrupt an existing flow.
                val host = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
                val port = preferences.getIntStringNotNull("byedpi_proxy_port", 1080)
                    .coerceIn(1, 65535)
                ByeDpiProxyCmdPreferences(context, selected, host, port)
            } else ByeDpiProxyPreferences.fromSharedPreferences(preferences, context)
            val args = when (proxy) {
                is ByeDpiProxyCmdPreferences -> proxy.args
                is ByeDpiProxyUIPreferences -> proxy.uiargs
            }
            val (defaultHost, defaultPort) = preferences.getProxyIpAndPort()
            val (host, port) = args.toList().checkIpAndPortInArgs()
            val resolvedPort = (port ?: defaultPort).toIntOrNull()
            require(resolvedPort != null && resolvedPort in 1..65535) {
                "Invalid local proxy port (expected 1..65535)"
            }
            val legacy = Configuration(
                args.toList(), host ?: defaultHost, resolvedPort,
                dns = preferences.getStringNotNull("dns_ip", "1.1.1.1").let { userDns ->
                    val link = NetworkEnvironment.capture(context)
                    val carrierDns = AccessPolicy.preferCarrierDnsOnNat64(
                        link,
                        preferences.getBoolean("nlic_mobile_dns64_auto", true),
                        userDns,
                    )
                    if (PrivateDnsUtils.getState(context) is PrivateDnsState.Configured || carrierDns) {
                        // No VPN DNS override: Android inherits the underlying
                        // resolver (including DNS64 on IPv6-only mobile paths).
                        ""
                    } else userDns
                },
                ipv6 = preferences.getBoolean("ipv6_enable", false),
                appListType = preferences.getStringNotNull("applist_type", "disable"),
                apps = preferences.getSelectedApps(),
            )
            // Legacy ByeDPI remains a separate, explicit mode.
            return legacy
        }

        private fun serviceClass(mode: Mode): Class<out Service> = when (mode) {
            Mode.VPN -> ByeDpiVpnService::class.java
            Mode.Proxy -> ByeDpiProxyService::class.java
        }

        @Suppress("DEPRECATION")
        fun running(context: Context): Pair<Mode, Int>? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE).sortedBy { it.activeSince }) {
                if (service.uid != Process.myUid() || service.pid <= 0) continue
                if (!service.started) continue
                val mode = Mode.entries.firstOrNull { service.service.className == serviceClass(it).name }
                    ?: continue
                return mode to service.pid
            }
            return null
        }
    }

    private val application = context.applicationContext as Application
    private val hello = CompletableDeferred<Unit>()
    private val configured = CompletableDeferred<Unit>()
    private val started = CompletableDeferred<Unit>()
    private val exited = CompletableDeferred<Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var binder: IBinder? = null
    private var remote: Messenger? = null
    private var bound = false
    private var connecting = false
    private var closing = false
    private var forceStopping = false
    private var startSent = false
    private var serviceStarted = initialPid > 0
    private var pid = initialPid
    private var processStartTime = if (initialPid > 0) readProcessStartTime() else null
    private var processStopped = false
    private var state = EngineProtocol.STATE_IDLE
    private var failure: String? = null
    internal val processId: Int get() = pid
    val needsConfiguration: Boolean get() = state == EngineProtocol.STATE_IDLE && !startSent
    var onStopping: (() -> Unit)? = null

    init {
        require(foreground || mode == Mode.Proxy)
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handler.post { onExit() }
    }

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (exited.isCompleted && message.what != EngineProtocol.FAILED) return@Handler true
        when (message.what) {
            EngineProtocol.HELLO_ACK -> {
                val remotePid = message.data.getInt(EngineProtocol.PID)
                if (remotePid <= 0 || remotePid == Process.myPid() || pid > 0 && pid != remotePid) {
                    hello.completeExceptionally(IllegalStateException("Invalid native process"))
                    return@Handler true
                }
                pid = remotePid
                if (processStartTime == null) processStartTime = readProcessStartTime()
                state = message.data.getInt(EngineProtocol.STATE)
                hello.complete(Unit)
                if (state != EngineProtocol.STATE_IDLE) configured.complete(Unit)
                when (state) {
                    EngineProtocol.STATE_RUNNING -> started.complete(Unit)
                    EngineProtocol.STATE_STOPPING -> if (!closing) onStopping?.invoke()
                }
                if (closing) sendStop()
            }
            EngineProtocol.STARTED -> {
                state = EngineProtocol.STATE_RUNNING
                started.complete(Unit)
            }
            EngineProtocol.CONFIGURED -> {
                state = EngineProtocol.STATE_STARTING
                configured.complete(Unit)
            }
            EngineProtocol.STOPPING -> {
                state = EngineProtocol.STATE_STOPPING
                if (!closing) onStopping?.invoke()
            }
            EngineProtocol.FAILED -> {
                failure = message.data.getString(EngineProtocol.ERROR) ?: "Native engine stopped"
                started.completeExceptionally(IllegalStateException(failure))
            }
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (exited.isCompleted) return
            if (binder != null && binder != service) {
                onExit()
                return
            }
            binder = service
            remote = Messenger(service)
            try {
                service.linkToDeath(deathRecipient, 0)
                send(EngineProtocol.HELLO)
                if (closing) sendStop()
                if (forceStopping) abort()
            } catch (_: RemoteException) {
                onExit()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = onExit()
        override fun onNullBinding(name: ComponentName) = onExit()
        override fun onBindingDied(name: ComponentName) = onExit()
    }

    suspend fun connect(launchService: Boolean = false) {
        check(!closing)
        if (!connecting) {
            connecting = true
            if (launchService && foreground) {
                ContextCompat.startForegroundService(application,
                    Intent(application, serviceClass(mode)).setAction(START_ACTION))
                serviceStarted = true
            }
            bound = application.bindService(
                Intent(application, serviceClass(mode)).setAction(
                    if (foreground) EngineProtocol.CONTROL_ACTION else EngineProtocol.BOUND_CONTROL_ACTION),
                connection,
                if (foreground) 0 else Context.BIND_AUTO_CREATE,
            )
            if (!foreground) serviceStarted = bound
            check(bound) { "Could not bind native engine" }
        }
        withTimeout(10_000) { hello.await() }
        check(!closing && !exited.isCompleted) { "Native engine stopped during startup" }
    }

    suspend fun start(configuration: Configuration) {
        connect(launchService = !serviceStarted)
        if (state == EngineProtocol.STATE_IDLE && !startSent) {
            require(configuration.args.isNotEmpty()) { "Proxy arguments are empty" }
            validateEndpoint(configuration.host, configuration.port)
            startSent = true
            send(EngineProtocol.START, Bundle().apply {
                putStringArray(EngineProtocol.ARGS, configuration.args.toTypedArray())
                putString(EngineProtocol.HOST, configuration.host)
                putInt(EngineProtocol.PORT, configuration.port)
                putString(EngineProtocol.DNS, configuration.dns)
                putBoolean(EngineProtocol.IPV6, configuration.ipv6)
                putString(EngineProtocol.APP_LIST_TYPE, configuration.appListType)
                putStringArray(EngineProtocol.APPS, configuration.apps.toTypedArray())
            })
        }
        awaitStarted()
    }

    suspend fun awaitConfigured() {
        configured.await()
    }

    suspend fun awaitStarted() {
        withTimeout(60_000) { started.await() }
        check(!closing && !exited.isCompleted) { failure ?: "Native engine stopped during startup" }
    }

    suspend fun awaitExit() {
        exited.await()
        throw IllegalStateException(failure ?: "Native engine process exited")
    }

    suspend fun awaitStopped() {
        while (processAlive()) {
            if (forceStopping) abort()
            delay(20)
        }
        processStopped = true
        onExit()
    }

    suspend fun stop() {
        closing = true
        if (!processStopped) {
            findPid()
            sendStop()
            if (withTimeoutOrNull(2_000) { awaitStopped(); true } == null) {
                Log.w(TAG, "Native engine did not stop in time, killing process $pid")
                abort()
            }
        }
        withTimeout(5_000) { awaitStopped() }
        if (foreground && serviceStarted) application.stopService(Intent(application, serviceClass(mode)))
        unbind()
    }

    private fun sendStop() {
        try {
            if (remote != null) send(EngineProtocol.STOP)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request native engine stop", e)
        }
    }

    private fun validateEndpoint(host: String, port: Int) {
        require(port in 1..65535) { "Invalid proxy port" }
        val address = host.removeSurrounding("[", "]")
        requireNotNull(Os.inet_pton(OsConstants.AF_INET, address)
            ?: Os.inet_pton(OsConstants.AF_INET6, address)) { "Invalid proxy address" }
    }

    fun abort() {
        closing = true
        forceStopping = true
        if (processStopped) return
        findPid()
        if (!foreground) unbind()
        if (pid <= 0 && serviceStarted) {
            cancelUnconnectedStart()
            if (pid <= 0) return
        }
        unbind()
        if (!processAlive()) {
            processStopped = true
            onExit()
        } else if (pid > 0 && pid != Process.myPid()) {
            val startTime = readProcessStartTime()
            val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val sameProcess = if (processStartTime != null && startTime != null) {
                processStartTime == startTime
            } else manager.runningAppProcesses.orEmpty().any {
                it.pid == pid && it.uid == Process.myUid() && it.processName == "${application.packageName}:native"
            }
            if (sameProcess) Process.killProcess(pid)
        }
    }

    @Suppress("DEPRECATION")
    private fun findPid() {
        if (pid > 0 || processStopped) return
        val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        pid = manager.getRunningServices(Int.MAX_VALUE).firstOrNull {
            it.uid == Process.myUid() && it.pid > 0 && it.service.className == serviceClass(mode).name
        }?.pid ?: manager.runningAppProcesses.orEmpty().firstOrNull {
            it.uid == Process.myUid() && it.processName == "${application.packageName}:native"
        }?.pid ?: 0
        if (pid > 0) processStartTime = readProcessStartTime()
    }

    @Suppress("DEPRECATION")
    private fun cancelUnconnectedStart() {
        unbind()
        if (foreground) application.stopService(Intent(application, serviceClass(mode)))
        val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val process = manager.runningAppProcesses.orEmpty().firstOrNull {
            it.uid == Process.myUid() && it.processName == "${application.packageName}:native"
        }
        if (process != null && process.pid > 0) {
            pid = process.pid
            processStartTime = readProcessStartTime()
            return
        }
        val pending = manager.getRunningServices(Int.MAX_VALUE).any {
            it.uid == Process.myUid() && it.service.className == serviceClass(mode).name
        }
        if (!pending && process == null) {
            serviceStarted = false
            processStopped = true
            onExit()
        }
    }

    private fun processAlive(): Boolean {
        if (processStopped) return false
        findPid()
        if (pid <= 0) return serviceStarted
        val startTime = readProcessStartTime()
        if (processStartTime != null && startTime != null && processStartTime != startTime) return false
        return try {
            Os.kill(pid, 0)
            true
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ESRCH || e.errno == OsConstants.EPERM) false else throw e
        }
    }

    private fun readProcessStartTime(): Long? = try {
        File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ').getOrNull(19)?.toLongOrNull()
    } catch (_: Exception) {
        null
    }

    private fun send(what: Int, data: Bundle = Bundle()) {
        val message = Message.obtain(null, what)
        message.replyTo = replies
        message.data = data
        checkNotNull(remote).send(message)
    }

    private fun onExit() {
        if (exited.isCompleted) return
        val error = IllegalStateException(failure ?: "Native engine process exited")
        unlink()
        unbind()
        exited.complete(Unit)
        hello.completeExceptionally(error)
        configured.completeExceptionally(error)
        started.completeExceptionally(error)
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        application.unbindService(connection)
    }

    private fun unlink() {
        try {
            binder?.unlinkToDeath(deathRecipient, 0)
        } catch (_: NoSuchElementException) {
        }
    }
}
