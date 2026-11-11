package io.github.romanvht.byedpi.subscription

/**
 * Socket-level fake-TTL / OOB strategies supported by pinned ByeDPI.
 * This is real *socket-controlled* desynchronization, NOT Zapret2 nfqws,
 * not raw IP packet injection and not a universal encrypted VPN bypass.
 *
 * These are only used for a dedicated 127.0.0.1 LIC-Bay SOCKS relay.
 * No arbitrary hostname, command line or subscription can be interpolated.
 */
internal object PacketDesyncPolicy {
    const val FAKE_TTL = "fake-ttl"
    const val DISOOB = "disoob"
    const val LEGACY = "legacy"
    const val PREF_KEY = "nlic_bay_packet_desync"
    const val MODE_KEY = "nlic_bay_packet_mode"

    fun command(mode: String?, compatibleRawReality: Boolean): String? {
        if (!compatibleRawReality) return null
        return when (mode) {
            FAKE_TTL -> "--proto=tls --fake -1 --ttl 8 --fake-tls-mod rand"
            DISOOB -> "--proto=tls --disoob 3+s"
            LEGACY -> null
            else -> null
        }
    }

    fun shouldUse(
        bayEnabled: Boolean,
        enabled: Boolean,
        isRawReality: Boolean,
    ): Boolean = bayEnabled && enabled && isRawReality
}
