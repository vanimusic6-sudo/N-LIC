package io.github.romanvht.byedpi.services

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.util.Log
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.utility.AccessType
import io.github.romanvht.byedpi.utility.NetworkEnvironment
import io.github.romanvht.byedpi.utility.LinkChangePolicy
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.createPauseNotification
import io.github.romanvht.byedpi.utility.registerNotificationChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

object ServiceManager {
    private const val TAG = "ServiceManager"
    private const val PAUSE_NOTIFICATION_ID = 3
    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + SupervisorJob() + CoroutineExceptionHandler {
            _, error -> Log.e(TAG, "Service command failed", error)
        }
    )
    private val commands = AtomicLong()
    private val mutex = Mutex()
    private var application: Application? = null
    private var session: Session? = null
    private var networkListener: ConnectivityManager.NetworkCallback? = null
    private var observedAccessType: AccessType = AccessType.UNKNOWN
    private var transitionJob: Job? = null

    // This field is intentionally process-local and contains only a fixed
    // diagnostic code. Never persist a native exception or subscription URI.
    @Volatile var lastCoreFailureCode: String? = null
        private set

    /**
     * Optional low-frequency network listener: only active while our VPN runs.
     * Avoid reconnecting for insignificant capability events or transient
     * UNKNOWN readings. Never restart a Proxy-mode Helping session.
     */
    private fun beginNetworkObservation(app: Application) {
        if (networkListener != null ||
            !app.getPreferences().getBoolean("nlic_restart_on_network_change", false)) return
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        observedAccessType = NetworkEnvironment.capture(app).type
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule()
            override fun onLost(network: Network) = schedule()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = schedule()

            private fun schedule() {
                transitionJob?.cancel()
                transitionJob = scope.launch {
                    delay(1_800)
                    val currentType = NetworkEnvironment.capture(app).type
                    val lastType = observedAccessType
                    if (currentType == AccessType.UNKNOWN) return@launch
                    observedAccessType = currentType
                    if (LinkChangePolicy.shouldRestart(
                            lastType, currentType,
                            app.getPreferences().getBoolean("nlic_restart_on_network_change", false),
                            session?.mode == Mode.VPN && session?.stopping == false &&
                                !TestService.isRunning,
                        )) {
                        Log.i(TAG, "Physical network type changed, reconnecting VPN via user opt-in")
                        restart(app, Mode.VPN)
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(listener)
            } else {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    listener,
                )
            }
            networkListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Could not register radio listener", e)
        }
    }

    private fun endNetworkObservation() {
        transitionJob?.cancel()
        transitionJob = null
        val listener = networkListener ?: return
        networkListener = null
        application?.let { app ->
            try {
                val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager
                manager?.unregisterNetworkCallback(listener)
            } catch (_: Exception) {
                // It may have been removed after the app process lost its network.
            }
        }
        observedAccessType = AccessType.UNKNOWN
    }


    private class Session(val mode: Mode, val engine: NativeEngine) {
        val configuration = CompletableDeferred<Configuration>()
        val configured = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Boolean>()
        val stopped = CompletableDeferred<Unit>()
        var stopping = false
        var job: Job? = null
    }

    fun start(context: Context, mode: Mode) {
        val app = context.applicationContext as Application
        scope.launch { waitStart(app, mode) }
    }

    suspend fun waitStart(context: Context, mode: Mode): Boolean {
        val app = context.applicationContext as Application
        val command = commands.incrementAndGet()
        return withContext(Dispatchers.Main.immediate) {
            startSession(app, mode, command)
        }
    }

    fun stop() {
        scope.launch { waitStop() }
    }

    suspend fun waitStop() {
        commands.incrementAndGet()
        withContext(Dispatchers.Main.immediate + NonCancellable) {
            application?.let {
                restore(it)
                (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSE_NOTIFICATION_ID)
            }
            val current = session ?: return@withContext
            stopSession(current)
        }
    }

    fun restart(context: Context, mode: Mode) {
        val app = context.applicationContext as Application
        val command = commands.incrementAndGet()
        scope.launch {
            if (TestService.isRunning) return@launch
            restore(app)
            session?.let { stopSession(it) }
            if (commands.get() == command) startSession(app, mode, command)
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext as Application
        scope.launch { restore(app) }
    }

    suspend fun handleAction(context: Context, action: String, mode: Mode, sourcePid: Int = 0) {
        val app = context.applicationContext as Application
        withContext(Dispatchers.Main.immediate) {
            if (TestService.isRunning) return@withContext
            restore(app)
            val activePid = session?.engine?.processId?.takeIf { it > 0 } ?: NativeEngine.running(app)?.second
            if (sourcePid > 0 && sourcePid != activePid) return@withContext
            if (sourcePid > 0 && (action == STOP_ACTION || action == PAUSE_ACTION) && session?.mode != mode) {
                return@withContext
            }
            when (action) {
                START_ACTION, RESUME_ACTION, RESTART_ACTION -> {
                    if (mode != Mode.VPN || VpnService.prepare(app) == null) {
                        val configured = CompletableDeferred<Boolean>()
                        val command = commands.incrementAndGet()
                        val startup = scope.launch {
                            try {
                                if (action == RESTART_ACTION) {
                                    session?.let { stopSession(it) }
                                }
                                startSession(app, mode, command, configured)
                            } finally {
                                configured.complete(false)
                            }
                        }
                        if (withTimeoutOrNull(8_000) { configured.await() } != true) {
                            startup.cancel()
                            if (commands.get() == command) {
                                session?.let {
                                    stopSession(it, await = false)
                                    it.engine.abort()
                                }
                                waitStop()
                            }
                        }
                    } else if (session?.mode == mode) {
                        waitStop()
                    }
                }
                STOP_ACTION, PAUSE_ACTION -> {
                    val activeMode = session?.mode ?: mode
                    waitStop()
                    if (session != null || TestService.isRunning) return@withContext
                    if (action == PAUSE_ACTION) {
                        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val channel = if (activeMode == Mode.VPN) "ByeDPIVpn" else "ByeDPI Proxy"
                        val name = if (activeMode == Mode.VPN) R.string.vpn_channel_name else R.string.proxy_channel_name
                        registerNotificationChannel(app, channel, name)
                        notifications.notify(PAUSE_NOTIFICATION_ID, createPauseNotification(
                            app, channel, R.string.notification_title, R.string.service_paused_text, activeMode,
                        ))
                    }
                }
            }
        }
    }

    private suspend fun startSession(
        app: Application,
        mode: Mode,
        command: Long,
        configured: CompletableDeferred<Boolean>? = null,
    ): Boolean = mutex.withLock {
        if (TestService.isRunning || commands.get() != command) return@withLock false
        restore(app)
        session?.let { previous ->
            if (!previous.stopping && previous.mode == mode) {
                configure(previous, app)
                configured?.complete(previous.configured.await())
                return@withLock previous.started.await()
            }
            stopSession(previous)
        }
        if (TestService.isRunning || commands.get() != command) return@withLock false

        val current = Session(mode, NativeEngine(app, mode))
        session = current
        if (app.getPreferences().getBoolean("nlic_core_mode", false)) {
            lastCoreFailureCode = null
        }
        if (mode == Mode.VPN) beginNetworkObservation(app)
        configure(current, app)
        // STARTING is not CONNECTED: only publish RUNNING after the native
        // engine passes its authenticated readiness probe.
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PAUSE_NOTIFICATION_ID)
        monitor(current, launchService = true)
        try {
            configured?.complete(current.configured.await())
            current.started.await()
        } catch (e: CancellationException) {
            stopSession(current, await = false)
            throw e
        }
    }

    private suspend fun stopSession(current: Session, await: Boolean = true) {
        if (!current.stopping) {
            current.stopping = true
            current.started.complete(false)
            current.job?.cancel()
        }
        if (await) current.stopped.await()
    }

    private fun configure(current: Session, app: Application) {
        if (current.configuration.isCompleted || current.started.isCompleted) return
        try {
            current.configuration.complete(NativeEngine.configuration(app))
        } catch (e: Exception) {
            current.configuration.completeExceptionally(e)
        }
    }

    private fun restore(app: Application) {
        application = app
        if (session != null || TestService.isRunning) return
        val running = NativeEngine.running(app)
        if (running == null) {
            endNetworkObservation()
            app.stopService(Intent(app, ByeDpiVpnService::class.java))
            app.stopService(Intent(app, ByeDpiProxyService::class.java))
            val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifications.cancel(ByeDpiVpnService.FOREGROUND_SERVICE_ID)
            notifications.cancel(ByeDpiProxyService.FOREGROUND_SERVICE_ID)
            if (appStatus.first != AppStatus.Halted) publish(appStatus.second, AppStatus.Halted, STOPPED_BROADCAST)
            return
        }
        val current = Session(running.first, NativeEngine(app, running.first, running.second))
        session = current
        if (running.first == Mode.VPN) beginNetworkObservation(app)
        // A recovered process may still be initializing. Query its native
        // session first; never infer a working VPN from service presence.
        monitor(current, launchService = false)
    }

    private fun monitor(current: Session, launchService: Boolean) {
        current.engine.onStopping = {
            if (session === current && !current.stopping) stop()
        }
        current.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failed = false
            try {
                current.engine.connect(launchService)
                coroutineScope {
                    launch {
                        current.engine.awaitConfigured()
                        current.configured.complete(true)
                    }
                    val ready = async { current.engine.awaitStarted() }
                    try {
                        if (current.engine.needsConfiguration) {
                            select {
                                ready.onAwait { }
                                current.configuration.onAwait { current.engine.start(it) }
                            }
                        } else {
                            ready.await()
                        }
                    } finally {
                        ready.cancel()
                    }
                }
                if (!current.stopping) {
                    lastCoreFailureCode = null
                    publish(current.mode, AppStatus.Running, STARTED_BROADCAST)
                    current.started.complete(true)
                }
                current.engine.awaitExit()
            } catch (_: CancellationException) {
                failed = !current.stopping
            } catch (e: Exception) {
                failed = !current.stopping
                if (failed) {
                    if (application?.getPreferences()?.getBoolean("nlic_core_mode", false) == true) {
                        val code = Regex("LIC-Core / ([A-Z_]+)")
                            .find(e.message.orEmpty())?.groupValues?.getOrNull(1)
                        lastCoreFailureCode = code?.takeIf {
                            it in setOf("SETUP", "CONFIG", "INTERFACES", "COMMAND",
                                "OUTBOUND", "CREDENTIAL_STORAGE", "BAY_UNREACHABLE",
                                "NO_SERVER", "TUNNEL_TEST", "ENGINE")
                        } ?: "ENGINE"
                        Log.e(TAG, "LIC-Core failed at safe stage $lastCoreFailureCode")
                    } else {
                        Log.e(TAG, "Native service failed", e)
                    }
                }
            } finally {
                current.configured.complete(false)
                withContext(NonCancellable) {
                    try {
                        current.engine.stop()
                        finish(current, failed && !current.stopping)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not confirm native process exit", e)
                        current.started.complete(false)
                        current.stopped.completeExceptionally(e)
                        publish(current.mode, AppStatus.Halted, FAILED_BROADCAST)
                        scope.launch {
                            current.engine.awaitStopped()
                            finish(current, failed = true)
                        }
                    }
                }
            }
        }
    }

    private fun finish(current: Session, failed: Boolean) {
        current.engine.onStopping = null
        if (session === current) {
            endNetworkObservation()
            session = null
            publish(current.mode, AppStatus.Halted, if (failed) FAILED_BROADCAST else STOPPED_BROADCAST)
        }
        current.started.complete(false)
        current.stopped.complete(Unit)
    }

    private fun publish(mode: Mode, status: AppStatus, action: String) {
        setStatus(status, mode)
        val sender = if (mode == Mode.VPN) Sender.VPN else Sender.Proxy
        application?.let {
            it.sendBroadcast(Intent(action).setPackage(it.packageName).putExtra(SENDER, sender.ordinal))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) QuickTileService.updateTile()
    }
}
