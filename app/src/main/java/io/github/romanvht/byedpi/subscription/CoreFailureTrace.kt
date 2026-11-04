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
        "BAY_PROXY", "BAY_UPSTREAM", "BAY_UDP",
        "CORE_START", "SETUP", "CONFIG", "INTERFACES", "COMMAND", "OUTBOUND",
        "CREDENTIAL_STORAGE", "CORE_SOCKS", "REMOTE_TEST",
        "TUN", "HEV", "RUNNING"
    )

    fun mark(context: Context, stage: String) {
        if (stage !in allowed) return
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

    fun recent(context: Context): String? {
        return runCatching {
        val file = AtomicFile(File(context.filesDir, NAME))
        if (!file.baseFile.exists() || file.baseFile.length() !in 1L..48L) return@runCatching null
        val value = file.openRead().bufferedReader(Charsets.US_ASCII).use { it.readText() }
        val timestamp = value.substringBefore(":").toLongOrNull() ?: return@runCatching null
        val stage = value.substringAfter(":", "")
        val elapsed = System.currentTimeMillis() - timestamp
        stage.takeIf { it in allowed && elapsed in 0..MAX_AGE_MS }
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, NAME).delete()
            File(context.filesDir, "$NAME.bak").delete()
        }
    }
}
