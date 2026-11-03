package io.github.romanvht.byedpi.utility

/** Reflects whether Android granted an actual physical Network handle. */
internal enum class ProbeRoute(val label: String) {
    BOUND_PHYSICAL("bound to a non-VPN Network"),
    SYSTEM_DEFAULT_UNBOUND("Android system default (UNBOUND; may use VPN)");

    companion object {
        fun forBoundNetwork(physicalNetworkAvailable: Boolean): ProbeRoute =
            if (physicalNetworkAvailable) BOUND_PHYSICAL else SYSTEM_DEFAULT_UNBOUND
    }
}
