package io.github.romanvht.byedpi.utility

internal object LinkChangePolicy {
    fun shouldRestart(
        previous: AccessType,
        current: AccessType,
        optedIn: Boolean,
        vpnRunning: Boolean,
    ): Boolean = optedIn && vpnRunning &&
        previous != AccessType.UNKNOWN &&
        current != AccessType.UNKNOWN &&
        previous != current
}
