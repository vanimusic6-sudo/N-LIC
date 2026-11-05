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
import io.github.romanvht.byedpi.core.NlicV2Proxy
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
import io.github.romanvht.byedpi.subscription.XrayRuntime
import io.github.romanvht.byedpi.subscription.XrayConfigCompiler
import io.github.romanvht.byedpi.subscription.RealityAssistPolicy
import io.github.romanvht.byedpi.subscription.RealityAssistMemory
import io.github.romanvht.byedpi.subscription.RealityAssistTrace
import io.github.romanvht.byedpi.subscription.XrayBayArgs
import io.github.romanvht.byedpi.subscription.CoreStartException
import io.github.romanvht.byedpi.subscription.NativeTunConfig
import io.github.romanvht.byedpi.subscription.CoreFailureTrace
import io.github.romanvht.byedpi.subscription.BayTransport
import io.github.romanvht.byedpi.subscription.LICBay
import io.github.romanvht.byedpi.subscription.LocalSocksAuth
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private var v2Engine: NlicV2Proxy? = null
    private var xrayEngine: XrayRuntime? = null
    private var localAuth: LocalSocksAuth? = null
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
                xrayEngine?.close()
                v2Engine?.stop()
                proxy?.stopProxy()
                tunnelThread?.join()
                proxyThread?.join()
                tunFd?.close()
            } catch (_: Throwable) {
                // JNI/Go errors can include profile details and passwords.
                // Never emit nested native exception messages to logcat.
                Log.w(TAG, "Failed to stop engine gracefully (details withheld)")
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
            // Only fixed phase identifiers appear in logs and UI: neither
            // outbound JSON nor VPN credentials may leave the native process.
            val xrayMode = config.args.firstOrNull() == "--nlic-xray"
            val v2Mode = config.args.firstOrNull() == "--nlic-v2"
            val coreMode = xrayMode ||
                config.args.firstOrNull() == "--nlic-libbox"
            var corePhase = "PREPARE"
            if (coreMode) CoreFailureTrace.mark(service, corePhase)
            try {
                // Auto must sample more than one real encrypted server.
                // The pinned Go delay probe has a 12 s per-node bound.
                withTimeout(if (xrayMode &&
                    (config.args.getOrNull(2) == "--nlic-auto" ||
                        service.getPreferences().getString(
                            "nlic_reality_tcp_utls", "profile") == RealityAssistPolicy.SMART))
                    145_000L else 50_000L) {
                    if (xrayMode) {
                        check(createTunnel != null) { "Xray VPN requires Android TUN" }
                        RealityAssistTrace.clear(service)
                        val bayEnabled = config.args.getOrNull(1) == "--nlic-bay"
                        val autoEnabled = config.args.getOrNull(2) == "--nlic-auto"
                        val bayPort = XrayConfigCompiler.BAY_PORT
                        corePhase = "CATALOG"; CoreFailureTrace.mark(service, corePhase)
                        val catalog = withContext(Dispatchers.IO) {
                            SubscriptionVault.read(service)
                        }
                        val secret = ByteArray(24).also {
                            SecureRandom().nextBytes(it)
                        }.joinToString("") { "%02x".format(it.toInt() and 255) }
                        val auth = LocalSocksAuth("nlic", secret)
                        localAuth = auth
                        // Keep the user's VPN protocol, node and REALITY keys.
                        // Smart mode only tries a browser uTLS fingerprint if the
                        // exact same node cannot pass authenticated HTTPS egress.
                        val assistMode = service.getPreferences().getString(
                            "nlic_reality_tcp_utls", RealityAssistPolicy.PROFILE
                        ) ?: RealityAssistPolicy.PROFILE
                        val realityFingerprint =
                            RealityAssistPolicy.explicitFingerprint(assistMode)
                        val validate: (io.github.romanvht.byedpi.subscription.SubscriptionNode) ->
                            Boolean = { candidate ->
                                XrayConfigCompiler.compile(
                                    candidate, auth, if (bayEnabled) bayPort else null, realityFingerprint
                                ) is XrayConfigCompiler.Result.Compiled
                            }
                        corePhase = "CONFIG_COMPILE"; CoreFailureTrace.mark(service, corePhase)
                        val candidates = if (autoEnabled) {
                            io.github.romanvht.byedpi.subscription.XrayAutoSelector.candidates(
                                catalog.allNodes, catalog.selectedNodeId, bayEnabled, validate
                            )
                        } else {
                            catalog.allNodes.filter { it.id == catalog.selectedNodeId && validate(it) }
                        }
                        if (candidates.isEmpty()) throw CoreStartException(
                            if (autoEnabled) "AUTO_NO_WORKING_SERVER" else "PROTOCOL_UNSUPPORTED"
                        )

                        if (bayEnabled) {
                            // Spawn a real, loopback-only ByeDPI SOCKS5 relay.
                            // Never use a direct fallback if the relay fails.
                            corePhase = "BAY_PROXY"; CoreFailureTrace.mark(service, corePhase)
                            val relayArgs = config.args.drop(3)
                            // Defense in depth: even IPC/tampered
                            // profiles cannot expose an unauthenticated
                            // SOCKS5 relay on the physical LAN.
                            if (!XrayBayArgs.validLoopback(relayArgs, bayPort)) {
                                throw CoreStartException("BAY_PROFILE")
                            }
                            val engine = withContext(Dispatchers.IO) { ByeDpiProxy() }
                            proxy = engine
                            proxyThread = runNative("ByeDPI-Bay") {
                                engine.startProxy(relayArgs.toTypedArray())
                            }
                            withTimeout(START_TIMEOUT) { awaitProxy("127.0.0.1", bayPort) }
                        }
                        val udpReady = if (bayEnabled && candidates.any {
                                LICBay.plan(it).transport == BayTransport.UDP_ASSOCIATE
                            }) {
                            corePhase = "BAY_UDP"; CoreFailureTrace.mark(service, corePhase)
                            withContext(Dispatchers.IO) {
                                TunnelHealthProbe.checkBayUdp(bayPort)
                            }
                        } else true
                        val eligible = candidates.filter {
                            !bayEnabled || udpReady ||
                                LICBay.plan(it).transport != BayTransport.UDP_ASSOCIATE
                        }
                        if (eligible.isEmpty()) throw CoreStartException("BAY_UDP_UNAVAILABLE")

                        // LIC-Auto tests a bounded set with the ACTUAL
                        // encrypted Xray outbound (not TCP endpoint ping).
                        // Every failed candidate is stopped before trying
                        // another. No VPN routes exist until a winner passes.
                        val selected = if (autoEnabled) {
                            val measurements = mutableListOf<
                                io.github.romanvht.byedpi.subscription.XrayAutoSelector.Measurement>()
                            for (candidate in eligible) {
                                corePhase = "AUTO_PROBE"; CoreFailureTrace.mark(service, corePhase)
                                val compiled = XrayConfigCompiler.compile(
                                    candidate, auth, if (bayEnabled) bayPort else null, realityFingerprint
                                ) as? XrayConfigCompiler.Result.Compiled ?: continue
                                val trial = XrayRuntime(service)
                                xrayEngine = trial
                                try {
                                    withContext(Dispatchers.IO) { trial.start(compiled.json) }
                                    withTimeout(5_000) {
                                        awaitProxy(config.host, config.port, auth)
                                    }
                                    val began = android.os.SystemClock.elapsedRealtime()
                                    val nativeMs = withContext(Dispatchers.IO) {
                                        trial.measureHttpsLatencyMs()
                                    }
                                    // Go core.Dial is certificate-validated
                                    // and bounded to 12 s. Never equate a
                                    // plain TCP or local SOCKS handshake with
                                    // an Auto candidate working over VPN.
                                    if (nativeMs != null) measurements +=
                                        io.github.romanvht.byedpi.subscription.XrayAutoSelector.Measurement(
                                            candidate.id,
                                            nativeMs ?: (android.os.SystemClock.elapsedRealtime() -
                                                began).coerceAtLeast(1),
                                        )
                                } catch (_: TimeoutCancellationException) {
                                    // Candidate timed out: do not promote it.
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Only a safe code is exposed; configs and
                                    // JNI exceptions may contain credentials.
                                } finally {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        trial.close()
                                    }
                                    if (xrayEngine === trial) xrayEngine = null
                                    delay(200) // release the previous SOCKS port
                                }
                            }
                            val best = io.github.romanvht.byedpi.subscription.XrayAutoSelector
                                .best(measurements) ?: throw CoreStartException("AUTO_NO_WORKING_SERVER")
                            eligible.first { it.id == best.id }
                        } else eligible.first()

                        val baselinePlan = XrayConfigCompiler.compile(
                            selected, auth, if (bayEnabled) bayPort else null
                        ) as? XrayConfigCompiler.Result.Compiled
                            ?: throw CoreStartException("PROTOCOL_UNSUPPORTED")
                        val baselineFingerprint = RealityAssistPolicy
                            .baselineFingerprint(baselinePlan.json)
                        val eligibleReality = baselineFingerprint != null
                        val adaptive = eligibleReality && !autoEnabled &&
                            assistMode == RealityAssistPolicy.SMART
                        val traceEligible = eligibleReality &&
                            (!autoEnabled || assistMode != RealityAssistPolicy.SMART)

                        // Persistent data contain only a salted node identifier,
                        // a coarse network class and a safe uTLS label.
                        val networkClass = if (adaptive) {
                            RealityAssistMemory.networkClass(service)
                        } else "other"
                        val cached = if (adaptive) RealityAssistMemory.load(
                            service, selected.id, networkClass, bayEnabled
                        ) else null
                        val measurements = mutableListOf<RealityAssistPolicy.Measurement>()
                        var calibratedChoice: String? = null

                        if (adaptive && cached == null && baselineFingerprint != null) {
                            // A single opt-in calibration on a new node/network.
                            // No parallel handshakes; never make a TUN before
                            // a working fingerprint is selected and verified.
                            for (fp in RealityAssistPolicy.calibrationCandidates(
                                baselineFingerprint
                            )) {
                                RealityAssistTrace.mark(service, when (fp) {
                                    RealityAssistPolicy.CHROME ->
                                        RealityAssistTrace.Event.CALIBRATING_CHROME
                                    RealityAssistPolicy.FIREFOX ->
                                        RealityAssistTrace.Event.CALIBRATING_FIREFOX
                                    RealityAssistPolicy.SAFARI ->
                                        RealityAssistTrace.Event.CALIBRATING_SAFARI
                                    else -> RealityAssistTrace.Event.CALIBRATING_ORIGINAL
                                })
                                val plan = XrayConfigCompiler.compile(
                                    selected, auth, if (bayEnabled) bayPort else null, fp
                                ) as? XrayConfigCompiler.Result.Compiled ?: continue
                                val trial = XrayRuntime(service)
                                xrayEngine = trial
                                var sample: Long? = null
                                try {
                                    corePhase = "XRAY_CALIBRATE"
                                    CoreFailureTrace.mark(service, corePhase)
                                    withContext(Dispatchers.IO) { trial.start(plan.json) }
                                    withTimeout(5_000) {
                                        awaitProxy(config.host, config.port, auth)
                                    }
                                    // Go measures authenticated HTTPS over the
                                    // actual REALITY outbound, not a TCP ping.
                                    sample = withContext(Dispatchers.IO) {
                                        trial.measureHttpsLatencyMs()
                                    }?.takeIf { it in 1L..30_000L }
                                } catch (cancelled: CancellationException) {
                                    if (cancelled !is TimeoutCancellationException)
                                        throw cancelled
                                } catch (_: Exception) {
                                    // Failed profile is not proof of DPI.
                                } finally {
                                    measurements += RealityAssistPolicy.Measurement(fp, sample)
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        trial.close()
                                    }
                                    if (xrayEngine === trial) xrayEngine = null
                                    delay(160) // release the authenticated SOCKS port
                                }
                            }
                            calibratedChoice = RealityAssistPolicy.chooseBest(
                                baselineFingerprint, measurements
                            )
                        }

                        // A cached tested winner is first. Cold calibration may
                        // choose Chrome, Firefox, Safari or the original node.
                        // All fallbacks preserve SNI, keys and the same server.
                        val preferred = cached?.fingerprint ?: calibratedChoice
                        val fingerprintAttempts = RealityAssistPolicy.candidates(
                            assistMode, baselinePlan, autoEnabled, preferred
                        )
                        RealityAssistTrace.mark(service, when {
                            assistMode == RealityAssistPolicy.PROFILE ->
                                RealityAssistTrace.Event.SKIPPED_DISABLED
                            autoEnabled && assistMode == RealityAssistPolicy.SMART ->
                                RealityAssistTrace.Event.SKIPPED_AUTO
                            !eligibleReality ->
                                RealityAssistTrace.Event.SKIPPED_UNSUPPORTED
                            else -> RealityAssistTrace.Event.STARTING_ORIGINAL
                        })
                        // Mark as successful only after Xray HTTPS AND Android
                        // TUN/HEV startup complete. SOCKS readiness alone is
                        // not successful end-to-end VPN connectivity.
                        var verifiedAssist: RealityAssistTrace.Event? = null
                        var verifiedFingerprint: String? = null
                        var verifiedHttpsMs: Long? = null
                        var xrayReady = false
                        for ((attemptIndex, fp) in fingerprintAttempts.withIndex()) {
                            if (traceEligible && assistMode == RealityAssistPolicy.SMART &&
                                attemptIndex > 0) {
                                RealityAssistTrace.mark(service, when (fp) {
                                    RealityAssistPolicy.FIREFOX ->
                                        RealityAssistTrace.Event.RETRYING_FIREFOX
                                    RealityAssistPolicy.CHROME ->
                                        RealityAssistTrace.Event.RETRYING_CHROME
                                    RealityAssistPolicy.SAFARI ->
                                        RealityAssistTrace.Event.RETRYING_SAFARI
                                    else -> RealityAssistTrace.Event.STARTING_ORIGINAL
                                })
                            }
                            val plan = XrayConfigCompiler.compile(
                                selected, auth, if (bayEnabled) bayPort else null,
                                if (assistMode == RealityAssistPolicy.SMART)
                                    fp else realityFingerprint
                            ) as? XrayConfigCompiler.Result.Compiled
                                ?: throw CoreStartException("PROTOCOL_UNSUPPORTED")

                            // Never add VPN routes or promote a candidate until
                            // a real HTTPS check through the encrypted core
                            // succeeds. The old trial is closed before the
                            // next fingerprint rebinds localhost SOCKS.
                            val xray = XrayRuntime(service)
                            xrayEngine = xray
                            var remoteCheckStarted = false
                            var passed = false
                            var verificationStarted = 0L
                            try {
                                corePhase = "XRAY_START"; CoreFailureTrace.mark(service, corePhase)
                                withContext(Dispatchers.IO) { xray.start(plan.json) }
                                corePhase = "XRAY_SOCKS"; CoreFailureTrace.mark(service, corePhase)
                                withTimeout(START_TIMEOUT) {
                                    awaitProxy(config.host, config.port, auth)
                                }
                                if (traceEligible) {
                                    RealityAssistTrace.mark(service, when {
                                        assistMode == RealityAssistPolicy.SMART ->
                                            when (fp) {
                                                RealityAssistPolicy.CHROME ->
                                                    RealityAssistTrace.Event.CHECKING_CHROME
                                                RealityAssistPolicy.FIREFOX ->
                                                    RealityAssistTrace.Event.CHECKING_FIREFOX
                                                RealityAssistPolicy.SAFARI ->
                                                    RealityAssistTrace.Event.CHECKING_SAFARI
                                                else ->
                                                    RealityAssistTrace.Event.CHECKING_ORIGINAL
                                            }
                                        assistMode == RealityAssistPolicy.FIREFOX ->
                                            RealityAssistTrace.Event.CHECKING_MANUAL_FIREFOX
                                        assistMode == RealityAssistPolicy.SAFARI ->
                                            RealityAssistTrace.Event.CHECKING_MANUAL_SAFARI
                                        else -> RealityAssistTrace.Event.CHECKING_MANUAL_CHROME
                                    })
                                }
                                verificationStarted =
                                    android.os.SystemClock.elapsedRealtime()
                                corePhase = "XRAY_HTTPS"; CoreFailureTrace.mark(service, corePhase)
                                val nativeHttpsOk = withContext(Dispatchers.IO) {
                                    xray.verifyHttpsViaCore()
                                }
                                if (!nativeHttpsOk) {
                                    remoteCheckStarted = true
                                    corePhase = "REMOTE_TEST"; CoreFailureTrace.mark(service, corePhase)
                                    withTimeout(if (adaptive) 10_000L else 28_000L) {
                                        awaitRemoteReadiness(config.host, config.port, auth)
                                    }
                                }
                                passed = true
                                xrayReady = true
                                verifiedFingerprint = fp
                                verifiedHttpsMs = (
                                    android.os.SystemClock.elapsedRealtime() -
                                        verificationStarted
                                ).coerceIn(1L, 30_000L)
                                if (traceEligible) {
                                    verifiedAssist = when {
                                        assistMode == RealityAssistPolicy.SMART ->
                                            when (fp) {
                                                RealityAssistPolicy.CHROME ->
                                                    RealityAssistTrace.Event.PASSED_ADAPTIVE_CHROME
                                                RealityAssistPolicy.FIREFOX ->
                                                    RealityAssistTrace.Event.PASSED_ADAPTIVE_FIREFOX
                                                RealityAssistPolicy.SAFARI ->
                                                    RealityAssistTrace.Event.PASSED_ADAPTIVE_SAFARI
                                                else -> RealityAssistTrace.Event.PASSED_ORIGINAL
                                            }
                                        assistMode == RealityAssistPolicy.FIREFOX ->
                                            RealityAssistTrace.Event.PASSED_MANUAL_FIREFOX
                                        assistMode == RealityAssistPolicy.SAFARI ->
                                            RealityAssistTrace.Event.PASSED_MANUAL_SAFARI
                                        else -> RealityAssistTrace.Event.PASSED_MANUAL_CHROME
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                // A remote-test timeout may indicate blocked
                                // egress. A local/native startup timeout never
                                // justifies changing TLS fingerprints.
                                if (cancelled !is TimeoutCancellationException ||
                                    !remoteCheckStarted ||
                                    attemptIndex == fingerprintAttempts.lastIndex) {
                                    throw cancelled
                                }
                            } catch (e: Exception) {
                                // Do not interpret a broken Xray core, invalid
                                // credentials or failed SOCKS authentication as
                                // DPI. Only retry after the outbound check.
                                if (!remoteCheckStarted ||
                                    attemptIndex == fingerprintAttempts.lastIndex) throw e
                            } finally {
                                if (!passed) {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        xray.close()
                                    }
                                    if (xrayEngine === xray) xrayEngine = null
                                    if (attemptIndex < fingerprintAttempts.lastIndex) {
                                        delay(250)
                                    }
                                }
                            }
                            if (passed) break
                        }
                        if (!xrayReady) throw CoreStartException("REALITY_EGRESS")
                        // Save only the actually verified Auto winner.
                        // Explicit manual selections are never overwritten.
                        if (autoEnabled) withContext(Dispatchers.IO) {
                            runCatching {
                                SubscriptionVault.select(service, selected.id)
                            }
                        }
                        val physical = NetworkEnvironment.capture(service)
                        val mtu = AccessPolicy.tunMtu(
                            physical,
                            service.getPreferences().getBoolean("nlic_mobile_safe_mtu", true)
                        ).coerceIn(1280, 1400)
                        corePhase = "XRAY_TUN"; CoreFailureTrace.mark(service, corePhase)
                        val descriptor = requireNotNull(createTunnel)(config, physical, mtu)
                        tunFd = descriptor
                        val tunnelConfig = buildString {
                            appendLine("tunnel:")
                            appendLine("  mtu: $mtu")
                            appendLine("misc:")
                            appendLine("  task-stack-size: 81920")
                            appendLine("socks5:")
                            appendLine("  address: 127.0.0.1")
                            appendLine("  port: ${config.port}")
                            appendLine("  udp: udp")
                            appendLine("  username: nlic")
                            appendLine("  password: ${auth.password}")
                        }
                        corePhase = "XRAY_HEV"; CoreFailureTrace.mark(service, corePhase)
                        tunnelThread = runNative("HEV") {
                            TProxyService.startTunnel(tunnelConfig, descriptor.fd)
                        }
                        delay(150)
                        check(tunnelThread?.isAlive == true) {
                            "Xray HEV tunnel exited during startup"
                        }
                        // Confirmed encrypted HTTPS + live Android TUN/HEV.
                        // Only now is a new preferred fingerprint persisted.
                        // Existing good cache remains fresh until its 12h TTL,
                        // so reconnects cannot cause constant rescanning.
                        if (adaptive) {
                            val chosen = verifiedFingerprint ?: RealityAssistPolicy.PROFILE
                            if (cached == null || cached.fingerprint != chosen) {
                                val calibrationMs = measurements.firstOrNull {
                                    it.fingerprint == verifiedFingerprint
                                }?.httpsMs
                                RealityAssistMemory.save(
                                    service, selected.id, networkClass, bayEnabled,
                                    chosen, calibrationMs ?: verifiedHttpsMs ?: 1000L
                                )
                            }
                        }
                        verifiedAssist?.let { RealityAssistTrace.mark(service, it) }
                    } else if (config.args.firstOrNull() == "--nlic-libbox") {
                        check(createTunnel != null) {
                            "LIC-Core requires VPN mode; proxy-only service is unsupported"
                        }
                        val preferences = service.getPreferences()
                        corePhase = "CATALOG"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        val saved = withContext(Dispatchers.IO) { SubscriptionVault.read(service) }
                        corePhase = "PREPARE"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        val bayEnabled = config.args.getOrNull(1) == "--nlic-bay"
                        val autoEnabled = config.args.getOrNull(2) == "--nlic-auto"
                        // LIC-Core DIRECT must not depend on the old ByeDPI
                        // command or proxy port. Parsing legacy flags even when
                        // Bay was OFF could reject a perfectly valid VPN node
                        // before sing-box was ever initialized.
                        val legacyArgs = if (bayEnabled) config.args.drop(3)
                            else emptyList()
                        val (legacyHost, legacyPort) = if (bayEnabled) {
                            val (flagHost, flagPort) = legacyArgs.checkIpAndPortInArgs()
                            val (savedHost, savedPort) = preferences.getProxyIpAndPort()
                            val host = flagHost ?: savedHost
                            val port = (flagPort ?: savedPort).toIntOrNull()
                                ?: error("Invalid LIC-Bay port")
                            check(host == "127.0.0.1") {
                                "LIC-Bay must listen on 127.0.0.1"
                            }
                            host to port
                        } else {
                            "127.0.0.1" to 1080 // No ByeDPI listener starts.
                        }
                        val bay = if (bayEnabled) SingBoxConfigCompiler.Bay.ON
                            else SingBoxConfigCompiler.Bay.OFF
                        // A random ephemeral secret limits other apps' access to
                        // the loopback SOCKS listener while LIC-Core is running.
                        val password = ByteArray(24).also { SecureRandom().nextBytes(it) }
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        val auth = LocalSocksAuth("nlic", password)
                        localAuth = auth
                        corePhase = "CONFIG_COMPILE"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        val compiled = if (autoEnabled) {
                            SingBoxConfigCompiler.compileAuto(saved.allNodes, bay, legacyPort, auth.password)
                        } else {
                            val node = saved.allNodes.firstOrNull { it.id == saved.selectedNodeId }
                                ?: error("Selected LIC-Core server is not available")
                            SingBoxConfigCompiler.compile(node, bay, legacyPort, auth.password)
                        }
                        val plan = compiled as? SingBoxConfigCompiler.Result.Compiled
                            ?: error("Selected LIC-Core configuration cannot be used")
                        if (bayEnabled) {
                            corePhase = "BAY_PROXY"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                            check(legacyArgs.isNotEmpty()) { "Missing LIC-Bay command" }
                            val engine = withContext(Dispatchers.IO) { ByeDpiProxy() }
                            proxy = engine
                            proxyThread = runNative("ByeDPI-Bay") {
                                engine.startProxy(legacyArgs.toTypedArray())
                            }
                            withTimeout(START_TIMEOUT) { awaitProxy(legacyHost, legacyPort) }
                            // The native ByeDPI revision supports SOCKS5
                            // CONNECT and UDP ASSOCIATE. Check the appropriate
                            // transport before starting libbox, never treat a
                            // TCP handshake as evidence a UDP server works.
                            if (!autoEnabled) {
                                val selected = saved.allNodes.firstOrNull {
                                    it.id == saved.selectedNodeId
                                } ?: error("LIC-Core / NO_SERVER")
                                val transport = LICBay.plan(selected).transport
                                    ?: error("LIC-Core / BAY_UNSUPPORTED")
                                corePhase = if (transport == BayTransport.UDP_ASSOCIATE)
                                    "BAY_UDP" else "BAY_UPSTREAM"
                                if (coreMode) CoreFailureTrace.mark(service, corePhase)
                                // A bare SOCKS5 CONNECT to a VLESS/Reality
                                // server is NOT a reliable readiness test:
                                // some DPI middleboxes and ByeDPI strategies
                                // open the real TCP connection only after a
                                // TLS ClientHello has been sent. Rejecting
                                // the node at this point makes valid TCP
                                // subscriptions fail as BAY_UNREACHABLE.
                                // The actual authenticated HTTPS-through-
                                // sing-box probe below validates TCP end-to-end.
                                if (transport == BayTransport.UDP_ASSOCIATE) {
                                    var bayReady = false
                                    repeat(3) { attempt ->
                                        if (!bayReady) {
                                            bayReady = withContext(Dispatchers.IO) {
                                                TunnelHealthProbe.checkBayUdp(legacyPort)
                                            }
                                        }
                                        if (!bayReady && attempt < 2) delay(450)
                                    }
                                    check(bayReady) { "LIC-Core / BAY_UDP_UNAVAILABLE" }
                                }
                            } else {
                                // A mixed URLTest set needs both capabilities
                                // whenever UDP candidates are present.
                                if (saved.allNodes.any {
                                        LICBay.plan(it).transport == BayTransport.UDP_ASSOCIATE
                                    }) {
                                    corePhase = "BAY_UDP"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                                    check(withContext(Dispatchers.IO) {
                                        TunnelHealthProbe.checkBayUdp(legacyPort)
                                    }) { "LIC-Core / BAY_UDP_UNAVAILABLE" }
                                }
                            }
                        }
                        // Native-first: sing-box opens ONE Android TUN using
                        // VpnService.Builder via our PlatformInterface.
                        // HEV is kept ONLY for the standalone legacy ByeDPI
                        // mode. This removes the double proxy/TUN loop and
                        // allows UDP and TCP to be handled by sing-box itself.
                        val physical = NetworkEnvironment.capture(service)
                        val mtu = AccessPolicy.tunMtu(
                            physical,
                            service.getPreferences().getBoolean("nlic_mobile_safe_mtu", true)
                        ).coerceIn(1280, 9000)
                        val compiledTun = NativeTunConfig.compile(plan.config, mtu)
                        corePhase = "CORE_START"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        val box = LibboxRuntime(service, openAndroidTun = {
                            check(tunFd == null) { "Only one VPN TUN per session" }
                            val open = createTunnel ?: error("Android VPN service missing")
                            val descriptor = open(config, physical, mtu)
                            tunFd = descriptor
                            descriptor.fd
                        })
                        boxEngine = box
                        withContext(Dispatchers.IO) { box.start(compiledTun) }
                        check(tunFd != null) { "Android TUN was not opened by libbox" }
                        corePhase = "CORE_SOCKS"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        withTimeout(START_TIMEOUT) { awaitProxy(config.host, config.port, auth) }
                        corePhase = "REMOTE_TEST"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        withTimeout(28_000) { awaitRemoteReadiness(config.host, config.port, auth) }
                    } else if (v2Mode) {
                        check(createTunnel != null) { "V2 proxy requires Android VPN mode" }
                        // Per-session random password; never store in preferences
                        // or logs. HEV authenticates to this private listener.
                        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        val auth = LocalSocksAuth("nlic", secret)
                        localAuth = auth
                        val native = NlicV2Proxy()
                        v2Engine = native
                        proxyThread = runNative("NlicV2") {
                            native.run(config.port, auth.username, auth.password, true)
                        }
                        withTimeout(START_TIMEOUT) {
                            awaitProxy(config.host, config.port, auth)
                        }
                    } else {
                        val engine = withContext(Dispatchers.IO) { ByeDpiProxy() }
                        proxy = engine
                        proxyThread = runNative("ByeDPI") { engine.startProxy(config.args.toTypedArray()) }
                        withTimeout(START_TIMEOUT) { awaitProxy(config.host, config.port) }
                    }
                    if (!coreMode) {
                        corePhase = "TUN"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        createTunnel?.let { create ->
                        val underlyingLink = NetworkEnvironment.capture(service)
                        val tuneMobile = service.getPreferences().getBoolean("nlic_mobile_safe_mtu", true)
                        val baseMtu = AccessPolicy.tunMtu(underlyingLink, tuneMobile)
                        // Bound V2's TUN datagram sizes on both Wi-Fi and
                        // cellular; set the exact same value in Android
                        // VpnService.Builder and HEV tunnel configuration.
                        val mtu = if (v2Mode) baseMtu.coerceIn(1280, 1400)
                            else baseMtu
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
                            localAuth?.let {
                                // Values are ASCII hex and never interpolated
                                // into shell commands or user-visible logs.
                                appendLine("  username: ${it.username}")
                                appendLine("  password: ${it.password}")
                            }
                        }
                        corePhase = "HEV"; if (coreMode) CoreFailureTrace.mark(service, corePhase)
                        tunnelThread = runNative("HEV") { TProxyService.startTunnel(tunnelConfig, fd.fd) }
                        // Do not publish RUNNING when the native tunnel exited immediately.
                        delay(100)
                        check(tunnelThread?.isAlive == true) { "HEV tunnel exited during startup" }
                        }
                    }
                    if (state != EngineProtocol.STATE_STOPPING) {
                        state = EngineProtocol.STATE_RUNNING
                        if (coreMode) CoreFailureTrace.clear(service)
                        reply(EngineProtocol.STARTED)
                    }
                }
            } catch (e: CancellationException) {
                if (xrayMode && state != EngineProtocol.STATE_STOPPING) {
                    val pending = RealityAssistTrace.recent(service)?.event
                    if (pending in setOf(
                        RealityAssistTrace.Event.STARTING_ORIGINAL,
                        RealityAssistTrace.Event.CHECKING_ORIGINAL,
                        RealityAssistTrace.Event.RETRYING_FIREFOX,
                        RealityAssistTrace.Event.RETRYING_CHROME,
                        RealityAssistTrace.Event.CHECKING_FIREFOX,
                        RealityAssistTrace.Event.CHECKING_CHROME,
                        RealityAssistTrace.Event.CHECKING_MANUAL_FIREFOX,
                        RealityAssistTrace.Event.CHECKING_MANUAL_CHROME
                    )) RealityAssistTrace.mark(service, RealityAssistTrace.Event.FAILED)
                }
                if (state != EngineProtocol.STATE_STOPPING) {
                    if (coreMode && e is TimeoutCancellationException) {
                        fail("LIC-Core / ${corePhase}_TIMEOUT")
                    } else if (coreMode) {
                        fail("LIC-Core / ${corePhase}")
                    } else fail("Engine startup timed out")
                }
                throw e
            } catch (e: Throwable) {
                if (xrayMode && state != EngineProtocol.STATE_STOPPING) {
                    val pending = RealityAssistTrace.recent(service)?.event
                    if (pending in setOf(
                        RealityAssistTrace.Event.STARTING_ORIGINAL,
                        RealityAssistTrace.Event.CHECKING_ORIGINAL,
                        RealityAssistTrace.Event.RETRYING_FIREFOX,
                        RealityAssistTrace.Event.RETRYING_CHROME,
                        RealityAssistTrace.Event.CHECKING_FIREFOX,
                        RealityAssistTrace.Event.CHECKING_CHROME,
                        RealityAssistTrace.Event.CHECKING_MANUAL_FIREFOX,
                        RealityAssistTrace.Event.CHECKING_MANUAL_CHROME
                    )) RealityAssistTrace.mark(service, RealityAssistTrace.Event.FAILED)
                }
                if (coreMode) {
                    // Some previous builds collapsed all start failures into
                    // ENGINE. Preserve the exact *safe phase*, but never the
                    // nested libbox/Go exception (may contain server URI).
                    val safeCode = when {
                        e is CoreStartException -> e.stage
                        e.message == "LIC-Core / BAY_UNREACHABLE" -> "BAY_UNREACHABLE"
                        e.message == "LIC-Core / BAY_UDP_UNAVAILABLE" -> "BAY_UDP_UNAVAILABLE"
                        e.message == "LIC-Core / BAY_UNSUPPORTED" -> "BAY_UNSUPPORTED"
                        e.message == "LIC-Core / NO_SERVER" -> "NO_SERVER"
                        else -> corePhase
                    }
                    Log.e(TAG, "LIC-Core failed at stage $safeCode (details withheld)")
                    fail("LIC-Core / $safeCode")
                } else {
                    Log.e(TAG, "Failed to start engine (details withheld)")
                    fail(if (configuration?.args?.firstOrNull() == "--nlic-v2")
                        "V2_NATIVE_START" else "BYEDPI_START")
                }
            }
        }
    }

    private fun runNative(name: String, run: () -> Int): Thread = thread(name = name) {
        val error = try {
            "$name stopped with code ${run()}"
        } catch (e: Throwable) {
            if (name == "ByeDPI-Bay" || name == "NlicV2" ||
                configuration?.args?.firstOrNull() in
                    setOf("--nlic-libbox", "--nlic-xray", "--nlic-v2")) {
                // Core may carry an ephemeral SOCKS password in the private
                // HEV YAML. Never log nested native exception strings.
                Log.e(TAG, "LIC-Core native task $name exited (${e.javaClass.simpleName}, redacted)")
                "$name: native engine failed"
            } else {
                // Legacy ByeDPI commands may include hostnames and local paths.
                Log.e(TAG, "$name failed (details withheld)")
                "$name: native engine failed"
            }
        }
        handler.post {
            if (state != EngineProtocol.STATE_STOPPING) {
                if (configuration?.args?.firstOrNull() in
                    setOf("--nlic-libbox", "--nlic-xray")) {
                    val code = when (name) {
                        "ByeDPI-Bay" -> "BAY_PROXY_EXIT"
                        "HEV" -> "HEV_EXIT"
                        else -> "ENGINE_EXIT"
                    }
                    fail("LIC-Core / $code")
                } else fail(error)
            }
        }
    }

    private suspend fun awaitProxy(host: String, port: Int, auth: LocalSocksAuth? = null) {
        val address = when (host) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> host
        }
        while (true) {
            val ready = withContext(Dispatchers.IO) {
                TunnelHealthProbe.checkLocal(address, port, auth)
            }
            if (ready) return
            delay(75)
        }
    }

    /** Real outbound HTTPS validation BEFORE adding Android routes. */
    private suspend fun awaitRemoteReadiness(host: String, port: Int, auth: LocalSocksAuth) {
        // Do not declare a working encrypted VPN dead solely because one
        // CDN blocks a captive-portal test path. Require a real validated TLS
        // + 204 response from either of two fixed public endpoints.
        // Four *independent* cert-verified HTTPS targets, not the same
        // two 204-only URLs twice. A blocked connectivity-check endpoint
        // alone must not make a healthy VPN look disconnected.
        val targets = listOf(
            "www.gstatic.com", "cp.cloudflare.com",
            "example.com", "www.wikipedia.org",
        )
        val outcomes = mutableListOf<TunnelHealthProbe.EgressResult>()
        for ((index, target) in targets.withIndex()) {
            val result = withContext(Dispatchers.IO) {
                TunnelHealthProbe.probe(host, port, auth, target)
            }
            if (result == TunnelHealthProbe.EgressResult.OK) return
            outcomes += result
            if (index < targets.lastIndex) delay(300)
        }
        // Differentiate local connection, SOCKS outbound, verified TLS and
        // HTTP response failures. Persist only enum names, not destinations.
        val mostFrequent = outcomes.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: TunnelHealthProbe.EgressResult.CONNECT
        throw CoreStartException("REMOTE_${mostFrequent.name}")
    }

    private fun fail(error: String) {
        if (state == EngineProtocol.STATE_STOPPING) return
        Log.e(TAG, if (error.matches(Regex("LIC-Core / [A-Z_]{2,64}")))
            error else "Engine stopped (details withheld)")
        if (configuration?.args?.firstOrNull() in
            setOf("--nlic-libbox", "--nlic-xray")) {
            // The service intentionally exits on a failed health check.
            // Persist only its credential-free code so the UI does not
            // confuse that controlled exit with a Go panic at REMOTE_TEST.
            val code = Regex("^LIC-Core / ([A-Z_]+)$")
                .matchEntire(error)?.groupValues?.getOrNull(1) ?: "ENGINE"
            CoreFailureTrace.markFailure(service, code)
        }
        state = EngineProtocol.STATE_STOPPING
        reply(EngineProtocol.FAILED, Bundle().apply {
            putString(EngineProtocol.ERROR, error)
        })
        // Messenger is one-way. Killing :native in the same call can race
        // with delivery to the UI, so the actual safe error phase is lost
        // and the app displays only ENGINE. Give Binder a bounded 350 ms to
        // deliver FAILED before terminating the failed service.
        handler.postDelayed({ terminate() }, 1_000L)
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
