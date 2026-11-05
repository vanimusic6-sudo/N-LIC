package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cross-process, user-verified same-node REALITY choice.
 *
 * The main-screen lab runs in the UI/isolated test process, while the real
 * VPN connection is created by another process. SharedPreferences is not
 * reliable for cross-process write/read consistency, so v3 uses AtomicFile.
 * Only an installed random salt and a hash of (nodeId, network class, Bay)
 * are used as filenames; no server IP, UUID, SNI, keys, SSID or traffic logs.
 *
 * A selected method remains until the user reruns the test or a confirmed
 * VPN fallback invalidates it. It cannot expire silently in 12 hours and
 * unexpectedly revert to the imported fingerprint.
 */
internal object RealityAssistMemory {
    private const val SALT = "nlic-reality-lab-salt-v3.txt"
    private const val PREFIX = "nlic-reality-choice-v3-"

    data class Winner(
        val fingerprint: String,
        val measuredMs: Long,
        val tcpTrick: String = RealityTcpTricks.STOCK,
    )

    fun networkClass(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return "other"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "other"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun installedSalt(context: Context): String {
        val store = AtomicFile(File(context.filesDir, SALT))
        val prior = runCatching {
            store.openRead().bufferedReader(Charsets.US_ASCII).use { it.readText() }
        }.getOrNull()
        if (prior != null && prior.matches(Regex("[a-f0-9]{32}"))) return prior

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        var output: java.io.FileOutputStream? = null
        try {
            output = store.startWrite()
            output.write(salt.toByteArray(Charsets.US_ASCII))
            store.finishWrite(output)
        } catch (error: Exception) {
            if (output != null) store.failWrite(output)
            throw error
        }
        return salt
    }

    private fun choiceFile(
        context: Context, nodeId: String, network: String, bay: Boolean,
    ): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("${installedSalt(context)}|$nodeId|$network|$bay")
                .toByteArray(Charsets.UTF_8))
        val safe = digest.take(16).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        return AtomicFile(File(context.filesDir, "$PREFIX$safe.txt"))
    }

    fun load(
        context: Context, nodeId: String, network: String, bay: Boolean,
    ): Winner? = runCatching {
        val f = choiceFile(context, nodeId, network, bay)
        if (!f.baseFile.exists() || f.baseFile.length() !in 1L..120L)
            return@runCatching null
        val raw = f.openRead().bufferedReader(Charsets.US_ASCII)
            .use { it.readText() }
        val parts = raw.split('|')
        if (parts.size != 4) return@runCatching null
        val fingerprint = parts[0].takeIf {
            it == RealityAssistPolicy.PROFILE ||
                it in RealityAssistPolicy.browserCandidates
        } ?: return@runCatching null
        val trick = parts[1].takeIf { it in RealityTcpTricks.variants }
            ?: return@runCatching null
        val ms = parts[2].toLongOrNull()?.takeIf { it in 1L..30_000L }
            ?: return@runCatching null
        val time = parts[3].toLongOrNull()
            ?: return@runCatching null
        if (time <= 0L || time > System.currentTimeMillis())
            return@runCatching null
        Winner(fingerprint, ms, trick)
    }.getOrNull()

    fun save(
        context: Context,
        nodeId: String, network: String, bay: Boolean,
        fingerprint: String, measuredMs: Long,
        tcpTrick: String = RealityTcpTricks.STOCK,
    ): Boolean {
        if (fingerprint != RealityAssistPolicy.PROFILE &&
            fingerprint !in RealityAssistPolicy.browserCandidates) return false
        if (measuredMs !in 1L..30_000L ||
            tcpTrick !in RealityTcpTricks.variants) return false
        return runCatching {
            val store = choiceFile(context, nodeId, network, bay)
            val payload = "$fingerprint|$tcpTrick|$measuredMs|" +
                System.currentTimeMillis()
            var stream: java.io.FileOutputStream? = null
            try {
                stream = store.startWrite()
                stream.write(payload.toByteArray(Charsets.US_ASCII))
                store.finishWrite(stream)
                true
            } catch (error: Exception) {
                if (stream != null) store.failWrite(stream)
                false
            }
        }.getOrDefault(false)
    }

    fun invalidate(
        context: Context, nodeId: String, network: String, bay: Boolean,
    ) {
        runCatching {
            choiceFile(context, nodeId, network, bay).delete()
        }
    }
}
