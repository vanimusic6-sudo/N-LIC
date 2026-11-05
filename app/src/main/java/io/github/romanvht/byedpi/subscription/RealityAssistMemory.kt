package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Short-lived per-node/coarse-network adaptive preference, NOT a VPN profile.
 * A per-install random salt hashes node IDs; no IP, SNI, server name, UUID,
 * password, subscription link, SSID or traffic content is persisted.
 */
internal object RealityAssistMemory {
    private const val STORE = "nlic_reality_adaptive_v1"
    private const val TTL_MS = 12L * 60 * 60 * 1000

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

    private fun prefs(context: Context) = context.getSharedPreferences(
        STORE, Context.MODE_PRIVATE
    )

    private fun key(context: Context, nodeId: String, network: String, bay: Boolean): String {
        val preferences = prefs(context)
        var salt = preferences.getString("salt", null)
        if (salt == null || salt.length != 32) {
            salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            preferences.edit().putString("salt", salt).commit()
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("$salt|$nodeId|$network|$bay").toByteArray(Charsets.UTF_8))
        return "v1_" + bytes.take(12).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
    }

    fun load(
        context: Context,
        nodeId: String,
        network: String,
        bay: Boolean,
    ): Winner? = runCatching {
        val preferences = prefs(context)
        val key = key(context, nodeId, network, bay)
        val recorded = preferences.getLong(key + "_at", 0L)
        if (System.currentTimeMillis() - recorded !in 0L..TTL_MS)
            return@runCatching null
        val fp = preferences.getString(key + "_fp", null)
        if (fp !in RealityAssistPolicy.browserCandidates &&
            fp != RealityAssistPolicy.PROFILE) return@runCatching null
        val ms = preferences.getLong(key + "_ms", 0L)
        if (ms !in 1L..30_000L) return@runCatching null
        val trick = preferences.getString(key + "_trick", RealityTcpTricks.STOCK)
            ?.takeIf { it in RealityTcpTricks.variants }
            ?: RealityTcpTricks.STOCK
        Winner(fp!!, ms, trick)
    }.getOrNull()

    fun save(
        context: Context,
        nodeId: String,
        network: String,
        bay: Boolean,
        fingerprint: String,
        measuredMs: Long,
        tcpTrick: String = RealityTcpTricks.STOCK,
    ) {
        if (fingerprint !in RealityAssistPolicy.browserCandidates &&
            fingerprint != RealityAssistPolicy.PROFILE) return
        if (measuredMs !in 1L..30_000L ||
            tcpTrick !in RealityTcpTricks.variants) return
        runCatching {
            val key = key(context, nodeId, network, bay)
            prefs(context).edit()
                .putString(key + "_fp", fingerprint)
                .putString(key + "_trick", tcpTrick)
                .putLong(key + "_ms", measuredMs)
                .putLong(key + "_at", System.currentTimeMillis())
                .apply()
        }
    }

    fun invalidate(context: Context, nodeId: String, network: String, bay: Boolean) {
        runCatching {
            val key = key(context, nodeId, network, bay)
            prefs(context).edit().remove(key + "_fp").remove(key + "_ms")
                .remove(key + "_trick").remove(key + "_at").apply()
        }
    }
}
