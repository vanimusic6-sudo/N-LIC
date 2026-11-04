package io.github.romanvht.byedpi.subscription

import io.github.romanvht.byedpi.utility.checkIpAndPortInArgs

/** Reject any ambiguous or externally exposed ByeDPI listener.
 * Only the intentional, single 127.0.0.1:11088 listener is allowed.
 * A user-supplied DPI strategy must not reopen the local relay to LAN.
 */
internal object XrayBayArgs {
    fun validLoopback(args: List<String>, expectedPort: Int): Boolean {
        if (expectedPort !in 1..65535 || args.isEmpty()) return false
        if (args.any { it in setOf("--", "--transparent", "-E", "-U", "--no-udp") })
            return false
        val ipFlags = args.count {
            it == "--ip" || it.startsWith("--ip=") ||
                (it.startsWith("-i") && !it.startsWith("--"))
        }
        val portFlags = args.count {
            it in setOf("--po", "--por", "--port") ||
                it.startsWith("--port=") ||
                (it.startsWith("-p") && !it.startsWith("--"))
        }
        if (ipFlags != 1 || portFlags != 1) return false
        val (host, port) = args.checkIpAndPortInArgs()
        return host == "127.0.0.1" && port?.toIntOrNull() == expectedPort
    }
}
