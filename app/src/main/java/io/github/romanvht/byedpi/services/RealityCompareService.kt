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
import io.github.romanvht.byedpi.subscription.RealityComparison
import io.github.romanvht.byedpi.subscription.SubscriptionNode
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.subscription.TunnelHealthProbe
import io.github.romanvht.byedpi.subscription.XrayConfigCompiler
import io.github.romanvht.byedpi.subscription.XrayRuntime
import io.github.romanvht.byedpi.utility.getPreferences
import java.security.SecureRandom
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null
    private val ipc by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == START && activeJob?.isActive != true) {
                val client = message.replyTo
                if (client != null) {
                    activeJob = scope.launch { compare(client) }
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = ipc.binder

    override fun onUnbind(intent: Intent?): Boolean {
        activeJob?.cancel()
        stopSelf()
        return false
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun reply(client: Messenger, what: Int, value: String) {
        try {
            client.send(Message.obtain(null, what).apply {
                data = Bundle().apply { putString(TEXT, value) }
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

    private suspend fun compare(client: Messenger) {
        try {
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
            if (baseline == null ||
                RealityAssistPolicy.baselineFingerprint(baseline.json) == null) {
                reply(client, ERROR,
                    "Выбранный профиль не поддерживает сравнение VLESS + TCP + REALITY.")
                return
            }

            val samples = mutableListOf<RealityComparison.Sample>()
            for ((index, fingerprint) in RealityComparison.sequence.withIndex()) {
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
                    "Проверка ${index + 1}/${RealityComparison.sequence.size} · $title\n" +
                        "Проверяется реальный HTTPS через тот же REALITY-сервер…")
                val sample = withContext(Dispatchers.IO) {
                    verify(node, auth, fingerprint)
                }
                samples += sample
                // We report the exact measured success, but a failure does
                // not identify censorship: server, DNS, TLS and upstream
                // availability can also be responsible.
                val result = if (sample.success)
                    "HTTPS прошёл · ${sample.latencyMs} мс"
                else "HTTPS не подтвердился"
                reply(client, PROGRESS,
                    "Проверка ${index + 1}/${RealityComparison.sequence.size} · $title: $result")
                if (index < RealityComparison.sequence.lastIndex) delay(350)
            }
            reply(client, FINISHED, RealityComparison.summarize(samples))
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

    private fun verify(
        node: SubscriptionNode,
        auth: LocalSocksAuth,
        fingerprint: String,
    ): RealityComparison.Sample {
        val alternate = fingerprint.takeUnless { it == RealityComparison.ORIGINAL }
        val compiled = XrayConfigCompiler.compile(
            node, auth, realityClientFingerprint = alternate
        ) as? XrayConfigCompiler.Result.Compiled
            ?: return RealityComparison.Sample(fingerprint, false, null)
        val runtime = XrayRuntime(applicationContext)
        try {
            // Stop and close each core before starting the next variation.
            // A successful local SOCKS negotiation is not enough: a real
            // HTTPS request, with trusted certificate verification, is required.
            runtime.start(compiled.json)
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
            if (!ready) return RealityComparison.Sample(fingerprint, false, null)

            val began = SystemClock.elapsedRealtime()
            val primary = TunnelHealthProbe.probe(
                "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth, "example.com"
            )
            val ok = if (primary == TunnelHealthProbe.EgressResult.OK) true
            else TunnelHealthProbe.probe(
                "127.0.0.1", XrayConfigCompiler.SOCKS_PORT, auth, "cp.cloudflare.com"
            ) == TunnelHealthProbe.EgressResult.OK
            val elapsed = (SystemClock.elapsedRealtime() - began).coerceAtLeast(1)
            return RealityComparison.Sample(fingerprint, ok, if (ok) elapsed else null)
        } catch (_: Exception) {
            return RealityComparison.Sample(fingerprint, false, null)
        } finally {
            runtime.close()
        }
    }
}
