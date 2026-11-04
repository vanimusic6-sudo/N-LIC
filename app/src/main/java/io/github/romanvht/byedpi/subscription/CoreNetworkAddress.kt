package io.github.romanvht.byedpi.subscription

/**
 * Strict normalization of Android LinkAddress for gomobile libbox.
 *
 * Android's IPv6 LinkAddress can contain a scope zone such as
 * fe80::abcd%wlan0/64.  Pinned sing-box 1.14's libbox converts the supplied
 * address with Go netip.MustParsePrefix(), which PANICS for any IPv6 scope
 * zone. That panic kills the isolated :native process without an Engine error
 * and was previously reported as generic "ENGINE".
 *
 * Never resolve a hostname. Return null for malformed input.
 */
internal object CoreNetworkAddress {
    fun prefix(hostAddress: String?, prefixLength: Int): String? {
        if (hostAddress.isNullOrBlank()) return null
        val numeric = hostAddress.substringBefore('%')
        if (numeric.isEmpty()) return null
        val ipv6 = numeric.contains(':')
        val maxBits = if (ipv6) 128 else 32
        if (prefixLength !in 0..maxBits) return null
        val allowed = if (ipv6) "0123456789abcdefABCDEF:." else "0123456789."
        if (!numeric.all { it in allowed }) return null
        if (!ipv6) {
            val parts = numeric.split('.')
            if (parts.size != 4 || parts.any {
                    it.isBlank() || it.length > 3 ||
                        (it.toIntOrNull() ?: -1) !in 0..255
                }) return null
        } else if (numeric.count { it == ':' } < 2) {
            return null
        }
        // Android already validated this InetAddress at its OS API boundary;
        // the conservative numeric guard prevents any domain DNS resolution.
        return "$numeric/$prefixLength"
    }
}
