package io.github.romanvht.byedpi.services

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import io.github.romanvht.byedpi.subscription.LocalSocksAuth
import io.github.romanvht.byedpi.subscription.RealityAssistPolicy
import io.github.romanvht.byedpi.subscription.RealityAssistLab
import io.github.romanvht.byedpi.subscription.RealityTcpTricks
import io.github.romanvht.byedpi.subscription.RealityComparison
import io.github.romanvht.byedpi.subscription.RealityTransportProbe
import io.github.romanvht.byedpi.subscription.RealityStreamProbe
import io.github.romanvht.byedpi.subscription.RealityStreamComparison
import io.github.romanvht.byedpi.subscription.SubscriptionNode
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.subscription.TunnelHealthProbe
import io.github.romanvht.byedpi.subscription.XrayConfigCompiler
import io.github.romanvht.byedpi.subscription.XrayRuntime
import io.github.romanvht.byedpi.utility.getPreferences
import java.security.SecureRandom
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * A bound-only, non-exported and isolated Android process for explicit A/B
 * testing. Never touches VpnService, TUN routes or the running native process.
 *
 * One selected node, fixed test HTTPS destinations, authenticated private
 * loopback SOCKS. Raw subscription JSON, secrets and target hostnames are
 * never sent to the UI process, external logs or persistent preferences.
 *
 * If native Go code crashes, only the isolated :realitycompare process dies.
 */
internal class RealityCompareService : Service() {
    companion object {
        const val START = 1
        const val PROGRESS = 2
        const val FINISHED = 3
        const val ERROR = 4
        const val TEXT = "text"
        const val COMPACT = "compact"
        const val TRIALS = "trials"
        const val ROWS = "rows"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    @Volatile private var activeStreamSocket: Socket? = null
    private val ipc by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == START && activeJob?.isActive != true) {
                val client = message.replyTo
                if (client != null) {
                    val trials = message.data?.getInt(
                        TRIALS, RealityComparison.QUICK_TRIALS
                    ) ?: RealityComparison.QUICK_TRIALS
                    activeJob = scope.launch { compare(client, trials) }
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = ipc.binder

    override fun onUnbind(intent: Intent?): Boolean {
        activeStreamSocket?.close()
        activeJob?.cancel()
        stopSelf()
        return false
    }

    override fun onDestroy() {
        activeStreamSocket?.close()
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun reply(
        client: Messenger, what: Int, value: String, compact: String? = null,
        rows: List<String>? = null,
    ) {
        try {
            client.send(Message.obtain(null, what).apply {
                data = Bundle().apply {
                    putString(TEXT, value)
                    if (compact != null) putString(COMPACT, compact)
                    if (rows != null) putStringArrayList(ROWS, ArrayList(rows))
                }
            })
        } catch (_: RemoteException) {
            activeJob?.cancel()
        }
    }

    private fun noOtherVpn(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private suspend fun compare(client: Messenger, trials: Int) {
        try {
            val allTest = trials == RealityAssistLab.MODE_ALL
            val streamTest = trials == RealityStreamComparison.TRIALS
            val schedule = if (streamTest)
                RealityStreamComparison.sequence else RealityComparison.schedule(trials)
            if (!allTest && schedule == null) {
                reply(client, ERROR, "Недопустимый режим сравнения.")
                return
            }
            if (!noOtherVpn()) {
                reply(client, ERROR,
                    "Отключи работающий VPN. Сравнение не должно проходить внутри другого VPN.")
                return
            }
            val prefs = getPreferences()
            if (prefs.getBoolean("nlic_core_auto", false) ||
                prefs.getBoolean("nlic_core_bay", false)) {
                reply(client, ERROR,
                    "Для сравнения отключи LIC-Auto и LIC-Bay. Настройки не изменены.")
                return
            }
            if (!XrayRuntime.installed()) {
                reply(client, ERROR, "В этой сборке отсутствует Xray-core.")
                return
            }
            val catalog = withContext(Dispatchers.IO) {
                SubscriptionVault.read(applicationContext)
            }
            val node = catalog.allNodes.firstOrNull { it.id == catalog.selectedNodeId }
                ?: run {
                    reply(client, ERROR, "Выбери один VLESS + TCP + REALITY сервер.")
                    return
                }
            val auth = LocalSocksAuth(
                "nlic",
                ByteArray(24).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
            )
            val baseline = XrayConfigCompiler.compile(node, auth)
                as? XrayConfigCompiler.Result.Compiled
            val baselineFp = baseline?.let {
                RealityAssistPolicy.baselineFingerprint(it.json)
            }
            if (baseline == null || baselineFp == null) {
                reply(client, ERROR,
                    "Выбранный профиль не поддерживает сравнение VLESS + TCP + REALITY.")
                return
            }
            if (allTest) {
                compareAll(client, node, auth, baselineFp)
                return
            }

            // A single raw TCP check is independent of the encrypted
            // REALITY handshake. Do not turn TCP success into a VPN verdict.
            val sameServer = RealityTransportProbe.endpoint(baseline.json)
            val tcpResult = withContext(Dispatchers.IO) {
                sameServer?.let { RealityTransportProbe.checkTcp(it) }
            }
            if (tcpResult != null) {
                reply(client, PROGRESS, if (tcpResult.connected)
                    "TCP до VPN-сервера доступен. Начинаем HTTPS-проверки…"
                else
                    "TCP до VPN-сервера не подтвердился. HTTPS-проверки всё равно выполняются…")
            }
            val samples = mutableListOf<RealityComparison.Sample>()
            val streamSamples = mutableListOf<RealityStreamComparison.Sample>()
            for ((index, fingerprint) in schedule.withIndex()) {
                coroutineContext.ensureActive()
                if (!noOtherVpn()) {
                    reply(client, ERROR,
                        "Во время проверки появился другой VPN. Сравнение остановлено.")
                    return
                }
                val title = when (fingerprint) {
                    RealityComparison.FIREFOX -> "Firefox"
                    RealityComparison.CHROME -> "Chrome"
                    else -> "Исходный профиль"
                }
                reply(client, PROGRESS,
                    "Проверка ${index + 1}/${schedule.size} · $title\n" +
                        "Проверяется реальный HTTPS через тот же REALITY-сервер…")
                if (streamTest) {
                    val sample = withContext(Dispatchers.IO) {
                        verifyStream(node, auth, fingerprint)
                    }
                    streamSamples += RealityStreamComparison.Sample(fingerprint, sample)
                    val outcome = if (sample.successful)
                        "Передано 512 КиБ через TLS"
                    else "Передача прервана · ${sample.failure ?: "UNKNOWN"}"
                    reply(client, PROGRESS,
                        "Проверка ${index + 1}/3 · $title: $outcome")
                } else {
                    val sample = withContext(Dispatchers.IO) {
                        verify(node, auth, fingerprint)
                    }
                    samples += sample
                    val result = if (sample.success)
                        "HTTPS прошёл · ${sample.latencyMs} мс"
                    else "HTTPS не подтвердился · этап ${sample.failureStage ?: "UNKNOWN"}"
                    reply(client, PROGRESS,
                        "Проверка ${index + 1}/${schedule.size} · $title: $result")
                }
                if (index < schedule.lastIndex) delay(350)
            }
            if (streamTest) {
                val report = RealityStreamComparison.summarize(streamSamples)
                reply(client, FINISHED, report.details, report.compact)
            } else {
                val summary = RealityComparison.report(samples, trials, tcpResult)
                reply(client, FINISHED, summary.details, summary.compact)
            }
        } catch (cancelled: CancellationException) {
            // Client left the screen; do not continue network testing.
            throw cancelled
        } catch (_: Exception) {
            reply(client, ERROR,
                "Проверка остановлена из-за ошибки ядра или сети. " +
                    "Параметры VPN остались без изменений.")
        } finally {
            activeJob = null
        }
    }

    /**
     * Explicit user-requested full audit. One node, sequential independent
     * Xray runtimes. Each row: two real TLS-verified HTTPS GETs and one
     * complete 512-KiB delivery, with no VPN service and no credential logs.
     */
    private suspend fun compareAll(
        client: Messenger, node: SubscriptionNode, auth: LocalSocksAuth,
        baselineFingerprint: String,
    ) {
        val browsers = RealityAssistLab.candidates(baselineFingerprint)
        val tricks = RealityTcpTricks.available(false)
            .filter { it != RealityTcpTricks.STOCK }
        val total = browsers.size + tricks.size
        val rows = mutableListOf<RealityAssistLab.Row>()

        suspend fun tryOne(fp: String, trick: String) {
            coroutineContext.ensureActive()
            if (!noOtherVpn()) {
                throw IllegalStateException("VPN is active during audit")
            }
            val count = rows.size + 1
            val label = RealityAssistLab.Row(fp, trick, null, null, false).title()
            reply(client, PROGRESS,
                "Проверка $count/$total · $label\nHTTPS 1/2 → HTTPS 2/2 → 512 КиБ…")
            val result = withContext(Dispatchers.IO) {
                verifySuite(node, auth, fp, trick)
            }
            rows += result
            reply(client, PROGRESS,
                "Проверка $count/$total · " + result.human())
            if (count < total) delay(200)
        }

        // First learn which browser fingerprint actually works best.
        for (fp in browsers) tryOne(fp, RealityTcpTricks.STOCK)
        val browser = RealityAssistLab.bestBrowser(rows)
        // Then test EVERY documented client-side transport on that browser.
        for (trick in tricks) tryOne(browser, trick)
        val details = RealityAssistLab.explanation(rows) + "\n\n" +
            rows.joinToString("\n") { it.human() }
        reply(client, FINISHED, details, RealityAssistLab.compact(rows),
            rows.map { it.encode() })
    }

    private fun verifySuite(
        node: SubscriptionNode, auth: LocalSocksAuth,
        fingerprint: String, trick: String,
    ): RealityAssistLab.Row {
        val fp = fingerprint.takeUnless { it == RealityAssistLab.PROFILE }
        val plan = XrayConfigCompiler.compile(
            node, auth, realityClientFingerprint = fp,
            realityTcpTrick = trick,
        ) as? XrayConfigCompiler.Result.Compiled
            ?: return RealityAssistLab.Row(
                fingerprint, trick, null, null, false, "CONFIG"
            )
        val runtime = XrayRuntime(applicationContext, recordCoreStage = false)
        var stage = "XRAY_START"
        var first: Long? = null
        var second: Long? = null
        var transferred = false
        try {
            runtime.start(plan.json)
            stage = "LOCAL_SOCKS"
            val end = SystemClock.elapsedRealtime() + 4_000L
            var ready = false
            while (SystemClock.elapsedRealtime() < end) {
                if (TunnelHealthProbe.checkLocal(
                        "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth)) {
                    ready = true
                    break
                }
                Thread.sleep(120)
            }
            if (!ready) return RealityAssistLab.Row(
                fingerprint, trick, null, null, false, "LOCAL_SOCKS"
            )

            stage = "HTTPS_TEST"
            fun tryHttps(): Long? {
                val primary = TunnelHealthProbe.probeDetailed(
                    "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth, "example.com"
                )
                if (primary.result == TunnelHealthProbe.EgressResult.OK)
                    return primary.totalMs
                val backup = TunnelHealthProbe.probeDetailed(
                    "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth,
                    "cp.cloudflare.com"
                )
                return if (backup.result == TunnelHealthProbe.EgressResult.OK)
                    // Fallback successful, but total includes failed primary.
                    primary.totalMs + backup.totalMs else null
            }
            first = tryHttps()
            second = tryHttps()
            if (first == null && second == null) return RealityAssistLab.Row(
                fingerprint, trick, null, null, false, "HTTPS_TEST"
            )

            stage = "HTTPS_STREAM"
            // Require complete transfer before marking a TCP trick working.
            val transfer = try {
                RealityStreamProbe.run(
                    "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth
                ) { socket -> activeStreamSocket = socket }
            } finally {
                activeStreamSocket = null
            }
            transferred = transfer.successful
            return RealityAssistLab.Row(
                fingerprint, trick, first, second, transferred,
                if (transferred) "" else (transfer.failure ?: "HTTPS_STREAM")
            )
        } catch (_: Exception) {
            return RealityAssistLab.Row(
                fingerprint, trick, first, second, transferred, stage
            )
        } finally {
            runtime.close()
        }
    }

    private fun verify(
        node: SubscriptionNode,
        auth: LocalSocksAuth,
        fingerprint: String,
    ): RealityComparison.Sample {
        val alternate = fingerprint.takeUnless { it == RealityComparison.ORIGINAL }
        val compiled = XrayConfigCompiler.compile(
            node, auth, realityClientFingerprint = alternate
        ) as? XrayConfigCompiler.Result.Compiled
            ?: return RealityComparison.Sample(fingerprint, false, null,
                failureStage = "CONFIG")
        val runtime = XrayRuntime(applicationContext, recordCoreStage = false)
        var stage = "XRAY_START"
        try {
            // Stop and close each core before starting the next variation.
            // A successful local SOCKS negotiation is not enough: a real
            // HTTPS request, with trusted certificate verification, is required.
            runtime.start(compiled.json)
            stage = "LOCAL_SOCKS"
            val deadline = SystemClock.elapsedRealtime() + 4_000L
            var ready = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (TunnelHealthProbe.checkLocal(
                        "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth)) {
                    ready = true
                    break
                }
                Thread.sleep(120)
            }
            if (!ready) return RealityComparison.Sample(
                fingerprint, false, null, failureStage = "LOCAL_SOCKS"
            )
            stage = "HTTPS_TEST"

            // Never time a mere open TCP socket as VPN authentication.
            // Capture separate target HTTPS probe phases. The SOCKS CONNECT
            // phase *can include* REALITY dials but is not a standalone
            // measurement of the REALITY handshake.
            val first = TunnelHealthProbe.probeDetailed(
                "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth, "example.com"
            )
            val second = if (first.result == TunnelHealthProbe.EgressResult.OK)
                null else TunnelHealthProbe.probeDetailed(
                    "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth, "cp.cloudflare.com"
                )
            val winner = when {
                first.result == TunnelHealthProbe.EgressResult.OK -> first
                second?.result == TunnelHealthProbe.EgressResult.OK -> second
                else -> second ?: first
            }
            val success = winner.result == TunnelHealthProbe.EgressResult.OK
            val total = first.totalMs + (second?.totalMs ?: 0L)
            return RealityComparison.Sample(
                profile = fingerprint,
                success = success,
                latencyMs = if (success) total else null,
                socksConnectMs = winner.socksConnectMs,
                targetTlsMs = winner.targetTlsMs,
                httpResponseMs = winner.httpResponseMs,
                failureStage = if (success) null else winner.result.name,
                secondEndpoint = second != null,
            )
        } catch (_: Exception) {
            return RealityComparison.Sample(
                fingerprint, false, null, failureStage = stage
            )
        } finally {
            runtime.close()
        }
    }

    /** One streaming sample; separate Xray runtime, same server and credentials. */
    private fun verifyStream(
        node: SubscriptionNode,
        auth: LocalSocksAuth,
        fingerprint: String,
    ): RealityStreamProbe.Result {
        val alternate = fingerprint.takeUnless { it == RealityComparison.ORIGINAL }
        val compiled = XrayConfigCompiler.compile(
            node, auth, realityClientFingerprint = alternate
        ) as? XrayConfigCompiler.Result.Compiled
            ?: return RealityStreamProbe.Result(false, 0, null, null, "CONFIG")
        val runtime = XrayRuntime(applicationContext, recordCoreStage = false)
        var stage = "XRAY_START"
        try {
            runtime.start(compiled.json)
            stage = "LOCAL_SOCKS"
            val deadline = SystemClock.elapsedRealtime() + 4_000L
            var ready = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (TunnelHealthProbe.checkLocal("127.0.0.1",
                        XrayConfigCompiler.SOCKS_PORT, auth)) {
                    ready = true
                    break
                }
                Thread.sleep(120)
            }
            if (!ready) return RealityStreamProbe.Result(
                false, 0, null, null, "LOCAL_SOCKS"
            )
            stage = "HTTPS_STREAM"
            try {
                return RealityStreamProbe.run(
                    "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth
                ) { socket -> activeStreamSocket = socket }
            } finally {
                activeStreamSocket = null
            }
        } catch (_: Exception) {
            return RealityStreamProbe.Result(false, 0, null, null, stage)
        } finally {
            runtime.close()
        }
    }

}
