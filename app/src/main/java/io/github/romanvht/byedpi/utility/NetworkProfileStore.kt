package io.github.romanvht.byedpi.utility

/**
 * Pure, user-opt-in link-type profile lookup. Does NOT rotate a running proxy
 * or infer that a tested profile works on the current carrier.
 */
internal object NetworkProfileStore {
    const val ENABLED_KEY = "nlic_network_profiles"

    fun key(type: AccessType): String? = when (type) {
        AccessType.WIFI -> "nlic_wifi_command"
        AccessType.CELLULAR -> "nlic_cellular_command"
        AccessType.ETHERNET -> "nlic_ethernet_command"
        else -> null
    }

    fun choose(
        enabled: Boolean,
        type: AccessType,
        manualCommand: String,
        saved: (String) -> String?,
    ): String {
        if (!enabled) return manualCommand
        val key = key(type) ?: return manualCommand
        val candidate = saved(key)?.trim() ?: return manualCommand
        if (candidate.length !in 2..2048 || !candidate.startsWith("-") ||
            candidate.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return manualCommand
        }
        return candidate
    }
}
