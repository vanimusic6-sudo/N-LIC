package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Credential-free cross-process last-startup-stage marker.
 *
 * A native Go panic can kill :native without ever sending EngineProtocol.FAILED.
 * This internal AtomicFile survives that crash and helps distinguish a native
 * CONFIG parser abort from a physical network / transport failure.
 *
 * The file records only a fixed stage identifier and timestamp. Never include
 * user server, IP, SNI, DNS answers, subscription URLs or throwable messages.
 */
internal object CoreFailureTrace {
    private const val NAME = "nlic-core-stage.txt"
    private const val MAX_AGE_MS = 180_000L
    private val allowed = setOf(
        "PREPARE", "CATALOG", "CONFIG_PREPARE", "CONFIG_COMPILE",
        "BAY_PROXY", "BAY_UPSTREAM", "BAY_UDP", "BAY_UNREACHABLE",
        "BAY_UDP_UNAVAILABLE", "BAY_UNSUPPORTED", "NO_SERVER", "ENGINE",
        "BAY_PROXY_EXIT", "BAY_PROFILE", "PROTOCOL_UNSUPPORTED", "ENGINE_EXIT",
        "REMOTE_CONNECT", "REMOTE_SOCKS", "REMOTE_TLS", "REMOTE_HTTP", "REMOTE_IO",
        "REMOTE_TLS_CERT", "REMOTE_TLS_TIMEOUT", "REMOTE_TLS_HANDSHAKE",
        "REMOTE_TLS_PROTOCOL", "REMOTE_TLS_EOF", "REMOTE_TLS_IO",
        "CORE_START", "SETUP", "CONFIG", "INTERFACES", "COMMAND", "OUTBOUND",
        "CREDENTIAL_STORAGE", "CORE_SOCKS", "REMOTE_TEST",
        "TUN", "HEV", "RUNNING", "XRAY_START", "XRAY_SOCKS",
        "XRAY_TUN", "XRAY_HEV", "XRAY_HTTPS", "XRAY_MODE_UNSUPPORTED"
    )

    fun mark(context: Context, stage: String) {
        if (!isAllowed(stage)) return
        runCatching {
            val file = AtomicFile(File(context.filesDir, NAME))
            val bytes = "${System.currentTimeMillis()}:$stage".toByteArray(Charsets.US_ASCII)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    /** Distinguish a *controlled* failed readiness check from a Go process crash.
     * A deliberate failed startup terminates :native too, and used to be
     * misreported as NATIVE_AT_REMOTE_TEST due to a Binder delivery race.
     */
    fun markFailure(context: Context, safeStage: String) {
        if (isSafeFailure(safeStage)) mark(context, "FAILED_$safeStage")
    }

    /** Persist controlled timeouts without reporting a native Go crash.
     * Never persist exception messages, VPN node names or credentials.
     */
    internal fun isSafeFailure(stage: String): Boolean =
        stage in allowed || (stage.endsWith("_TIMEOUT") &&
            stage.removeSuffix("_TIMEOUT") in allowed)

    private fun isAllowed(stage: String): Boolean = stage in allowed ||
        (stage.startsWith("FAILED_") &&
            isSafeFailure(stage.removePrefix("FAILED_")))

    fun recent(context: Context): String? {
        return runCatching {
        val file = AtomicFile(File(context.filesDir, NAME))
        if (!file.baseFile.exists() || file.baseFile.length() !in 1L..48L) return@runCatching null
        val value = file.openRead().bufferedReader(Charsets.US_ASCII).use { it.readText() }
        val timestamp = value.substringBefore(":").toLongOrNull() ?: return@runCatching null
        val stage = value.substringAfter(":", "")
        val elapsed = System.currentTimeMillis() - timestamp
        stage.takeIf { isAllowed(it) && elapsed in 0..MAX_AGE_MS }
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, NAME).delete()
            File(context.filesDir, "$NAME.bak").delete()
        }
    }
}
