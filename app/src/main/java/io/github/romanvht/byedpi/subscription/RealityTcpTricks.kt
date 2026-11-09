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
    // LIC-Duet: two TLS records in one authenticated REALITY connection.
    // This is NOT Zapret2's fake-versus-real TCP sequence desynchronization.
    const val DUET = "lic-duet"
    const val DUET_GATE = "lic-duet-gate"
    const val DUET_MSS = "lic-duet-mss"
    const val TRIO = "lic-trio"
    const val FIRST_WRITE = "lic-first-write"
    const val FIRST_BURST = "lic-first-burst"
    const val SPLIT_SOFT = "split-soft"
    const val SPLIT_EARLY = "split-early"
    const val SPLIT_ZERO = "split-zero"
    const val MSS_1200 = "mss1200"
    const val MSS_900 = "mss900"
    const val TFO_OFF = "tfo-off"
    const val IDLE_KEEPALIVE = "idle-keepalive"

    // The first set changes packetization; the last two change TCP
    // connection/idle behavior. They are not all DPI bypasses.
    val variants = listOf(STOCK, DUET, DUET_GATE, DUET_MSS, TRIO,
        FIRST_WRITE, FIRST_BURST,
        SPLIT_SOFT, SPLIT_EARLY, SPLIT_ZERO,
        MSS_1200, MSS_900, TFO_OFF, IDLE_KEEPALIVE)

    // An ON switch must materially change the first outbound, even without
    // an earlier measurement or a manually chosen profile.
    const val DEFAULT_HELPER = DUET

    private val firstFlight = setOf(DUET, DUET_GATE, DUET_MSS, TRIO,
        FIRST_WRITE, FIRST_BURST,
        SPLIT_SOFT, SPLIT_EARLY, SPLIT_ZERO)

    data class Measurement(val trick: String, val httpsMs: Long?)
    data class Trial(val fingerprint: String?, val trick: String)

    fun label(trick: String): String = when (trick) {
        DUET -> "LIC-Duet · два TLS-фрагмента"
        DUET_GATE -> "LIC-Duet Gate · раннее разделение"
        DUET_MSS -> "LIC-Duet Frame · TLS + MSS"
        TRIO -> "LIC-Trio · три TLS-фрагмента"
        FIRST_WRITE -> "LIC-FirstWrite · начало TCP-потока"
        FIRST_BURST -> "LIC-FirstBurst · первые две записи"
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
            it.trick in variants && it.httpsMs != null && it.httpsMs in 1L..30_000L
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
     * Helper-first, never calibration-first. The first attempt ALWAYS has
     * a real first-flight modification (unless a separate ByeDPI relay owns
     * the physical socket). Stock REALITY is *only* a final fallback after
     * helper variants fail; it is never offered as a 'helper success'.
     *
     * We do not blindly combine profiles; only curated and bounded Xray
     * configurations are generated here. A prior successful helper comes
     * first. A stored stock result cannot silently disable the switch.
     */
    fun attempts(
        preferredFp: String?,
        preferredTrick: String,
        browsers: List<String?>,
        baselineFp: String,
        bay: Boolean,
        pinnedFingerprint: Boolean = false,
    ): List<Trial> {
        val candidateFp = if (pinnedFingerprint) {
            preferredFp?.takeIf { it in RealityAssistPolicy.browserCandidates }
        } else preferredFp?.takeIf {
            it in RealityAssistPolicy.browserCandidates && it != baselineFp
        } ?: if (baselineFp == RealityAssistPolicy.CHROME) null
            else RealityAssistPolicy.CHROME
        if (bay) return listOf(Trial(preferredFp, STOCK))
        val chosen = preferredTrick.takeIf { it in firstFlight } ?: DEFAULT_HELPER
        // At most five resource-intensive helper attempts on failure.
        // A *verified* stock path remains available after them; no direct
        // Internet fallback and no pre-connection probe matrix.
        val helper = listOf(
            Trial(candidateFp, chosen),
            Trial(candidateFp, DUET),
            Trial(candidateFp, DUET_GATE),
            Trial(candidateFp, FIRST_WRITE),
            Trial(candidateFp, DUET_MSS),
            Trial(candidateFp, TRIO),
        ).distinctBy { (it.fingerprint ?: baselineFp) to it.trick }.take(5)
        val stock = (listOf(candidateFp, null) + browsers)
            .map { Trial(it, STOCK) }
            .distinctBy { (it.fingerprint ?: baselineFp) to it.trick }.take(2)
        return helper + stock
    }

    /**
     * Only a confirmed encrypted egress failure is a reason to reconfigure
     * Xray and retry. Native startup, configuration, local SOCKS and user
     * cancellations must not create a hot restart loop.
     */
    fun retryEgressOnly(adaptive: Boolean, hasMore: Boolean, outboundProbeFailed: Boolean) =
        adaptive && hasMore && outboundProbeFailed

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

        if (trick in firstFlight) {
            // FinalMask applies after REALITY transport processing. TLS
            // record segmentation and TCP-stream write slicing preserve
            // byte order; neither injects invalid or forged packets.
            if (stream.has("finalmask")) return false
            val settings = JsonObject().apply {
                // TLS-record fragmentation and raw first-write slicing
                // are different mechanisms. Both preserve the byte stream.
                addProperty("packets", when (trick) {
                    FIRST_WRITE -> "1"
                    FIRST_BURST -> "1-2"
                    else -> "tlshello"
                })
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
                    DUET, DUET_MSS -> {
                        add("lengths", strings("160-220"))
                        add("delays", strings("3-5"))
                        // Xray counts *pieces*, not cut points:
                        // 2 pieces = one split into two TLS records.
                        addProperty("maxSplit", "2")
                    }
                    DUET_GATE -> {
                        add("lengths", strings("48-80"))
                        add("delays", strings("7-11"))
                        addProperty("maxSplit", "2")
                    }
                    TRIO -> {
                        add("lengths", strings("80-112", "160-192"))
                        add("delays", strings("2-4", "3-5"))
                        addProperty("maxSplit", "3")
                    }
                    FIRST_WRITE -> {
                        add("lengths", strings("112-160"))
                        add("delays", strings("3-5"))
                        addProperty("maxSplit", "2")
                    }
                    FIRST_BURST -> {
                        add("lengths", strings("144-200"))
                        add("delays", strings("1-3"))
                        addProperty("maxSplit", "2")
                    }
                }
            }
            // Build the two-layer profile atomically: never partially alter
            // imported socket options if an existing value conflicts.
            val originalSocket = stream.getAsJsonObject("sockopt")
            if (trick == DUET_MSS && originalSocket?.has("tcpMaxSeg") == true)
                return false
            stream.add("finalmask", JsonObject().apply {
                add("tcp", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "fragment")
                        add("settings", settings)
                    })
                })
            })
            if (trick == DUET_MSS) {
                val socket = originalSocket ?: JsonObject().also {
                    stream.add("sockopt", it)
                }
                socket.addProperty("tcpMaxSeg", 1200)
            }
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
