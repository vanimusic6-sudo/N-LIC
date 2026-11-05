package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Bounded, server-independent TCP/REALITY client-side countermeasures.
 *
 * These are actual Xray stream/socket options, not dummy UI profiles.
 * They preserve REALITY authentication, SNI, UUID, shortId and Vision flow.
 * The kernel may coalesce writes, so a fragmentation configuration is
 * NOT proof of the exact TCP segments seen by an outside observer.
 */
internal object RealityTcpTricks {
    const val STOCK = "stock"
    const val SPLIT_SOFT = "split-soft"
    const val SPLIT_EARLY = "split-early"
    const val SPLIT_ZERO = "split-zero"
    const val MSS_1200 = "mss1200"
    const val MSS_900 = "mss900"
    const val TFO_OFF = "tfo-off"
    const val IDLE_KEEPALIVE = "idle-keepalive"

    // The first set changes packetization; the last two change TCP
    // connection/idle behavior. They are not all DPI bypasses.
    val variants = listOf(STOCK, SPLIT_SOFT, SPLIT_EARLY, SPLIT_ZERO,
        MSS_1200, MSS_900, TFO_OFF, IDLE_KEEPALIVE)

    data class Measurement(val trick: String, val httpsMs: Long?)
    data class Trial(val fingerprint: String?, val trick: String)

    fun label(trick: String): String = when (trick) {
        SPLIT_SOFT -> "разделение ClientHello"
        SPLIT_EARLY -> "раннее разделение ClientHello"
        SPLIT_ZERO -> "TLS-записи без паузы"
        MSS_1200 -> "TCP MSS 1200"
        MSS_900 -> "TCP MSS 900"
        TFO_OFF -> "TCP Fast Open выключен"
        IDLE_KEEPALIVE -> "TCP KeepAlive 30/12"
        else -> "обычный TCP"
    }

    /**
     * SOCKS relay used by LIC-Bay establishes its own physical TCP
     * connection, so these options on Xray's outbound would not act on
     * the real remote socket. Do not claim they work through LIC-Bay.
     */
    fun available(bay: Boolean): List<String> =
        if (bay) listOf(STOCK) else variants

    /**
     * Favors a working imported transport until another one beats
     * authenticated HTTPS by >=90ms AND >=15%. If normal TCP cannot
     * authenticate, take a real verified working candidate.
     */
    fun chooseBest(samples: List<Measurement>): String {
        val valid = samples.filter {
            it.trick in variants && it.httpsMs in 1L..30_000L
        }.distinctBy { it.trick }
        val baseline = valid.firstOrNull { it.trick == STOCK }
        val fastest = valid.minByOrNull { it.httpsMs!! } ?: return STOCK
        if (baseline == null) return fastest.trick
        val old = baseline.httpsMs!!
        val new = fastest.httpsMs!!
        return if (fastest.trick != STOCK &&
            old - new >= 90L && (old - new) * 100 >= old * 15)
            fastest.trick else STOCK
    }

    /**
     * Cache first, then the chosen browser's unchanged TCP, then fallback
     * to the imported browser and verified alternative uTLS. Finally try a
     * few known compatible packetization profiles on the imported browser
     * if and only if all ordinary candidates fail. Always same node.
     */
    fun attempts(
        preferredFp: String?,
        preferredTrick: String,
        browsers: List<String?>,
        baselineFp: String,
        bay: Boolean,
    ): List<Trial> {
        val chosen = preferredTrick.takeIf { it in available(bay) } ?: STOCK
        val first = Trial(preferredFp, chosen)
        val rows = listOf(first, Trial(preferredFp, STOCK)) +
            browsers.map { Trial(it, STOCK) } +
            if (bay) emptyList() else listOf(
                Trial(null, SPLIT_SOFT),
                Trial(null, SPLIT_ZERO),
                Trial(null, MSS_1200)
            )
        return rows.distinctBy { (it.fingerprint ?: baselineFp) to it.trick }
            .take(9)
    }

    /**
     * Materially alter outgoing REALITY TCP packetization/handshake only
     * with fixed well-bounded Xray options. Reject collisions with imported
     * stream/finalmask/sockopt settings rather than overwrite user options.
     */
    fun apply(outbound: JsonObject, trick: String, bay: Boolean): Boolean {
        if (trick !in available(bay)) return false
        if (trick == STOCK) return true
        if (outbound.get("protocol")?.asString != "vless") return false
        val stream = outbound.getAsJsonObject("streamSettings") ?: return false
        if (stream.get("network")?.asString != "raw" ||
            stream.get("security")?.asString != "reality") return false

        if (trick.startsWith("split-")) {
            // finalmask is applied after REALITY encryption. All splits
            // are explicit TLS-record slices, not raw random-byte injection.
            if (stream.has("finalmask")) return false
            val settings = JsonObject().apply {
                addProperty("packets", "tlshello")
                when (trick) {
                    SPLIT_SOFT -> {
                        add("lengths", strings("180-260"))
                        add("delays", strings("2-5"))
                        addProperty("maxSplit", "4")
                    }
                    SPLIT_EARLY -> {
                        add("lengths", strings("64-128", "160-220"))
                        add("delays", strings("4-8"))
                        addProperty("maxSplit", "5")
                    }
                    SPLIT_ZERO -> {
                        add("lengths", strings("220-320"))
                        add("delays", strings("0"))
                        addProperty("maxSplit", "4")
                    }
                }
            }
            stream.add("finalmask", JsonObject().apply {
                add("tcp", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "fragment")
                        add("settings", settings)
                    })
                })
            })
            return true
        }

        val socket = stream.getAsJsonObject("sockopt") ?: JsonObject().also {
            stream.add("sockopt", it)
        }
        return when (trick) {
            MSS_1200, MSS_900 -> {
                if (socket.has("tcpMaxSeg")) false else {
                    socket.addProperty("tcpMaxSeg",
                        if (trick == MSS_1200) 1200 else 900)
                    true
                }
            }
            TFO_OFF -> {
                if (socket.has("tcpFastOpen")) false else {
                    socket.addProperty("tcpFastOpen", false)
                    true
                }
            }
            IDLE_KEEPALIVE -> {
                if (socket.has("tcpKeepAliveIdle") ||
                    socket.has("tcpKeepAliveInterval")) false else {
                    socket.addProperty("tcpKeepAliveIdle", 30)
                    socket.addProperty("tcpKeepAliveInterval", 12)
                    true
                }
            }
            else -> false
        }
    }

    private fun strings(vararg values: String) = JsonArray().apply {
        values.forEach { add(it) }
    }
}
