package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Non-secret, cross-process status of the last LIC-Assist startup attempt.
 *
 * Settings cannot prove that the engine actually tried the alternate uTLS
 * fingerprint. The native Xray process records only a finite enum here,
 * never a server, UUID, SNI, link, key, fingerprint bytes or network content.
 *
 * The main process reads this small AtomicFile on the control screen.
 * State is cleared at the beginning of each new Xray connection attempt.
 */
internal object RealityAssistTrace {
    private const val FILE = "nlic-reality-assist-status.txt"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1_000

    enum class Event {
        SKIPPED_DISABLED,
        SKIPPED_AUTO,
        SKIPPED_UNSUPPORTED,
        STARTING_ORIGINAL,
        CHECKING_ORIGINAL,
        RETRYING_FIREFOX,
        RETRYING_CHROME,
        CHECKING_FIREFOX,
        CHECKING_CHROME,
        CHECKING_MANUAL_FIREFOX,
        CHECKING_MANUAL_CHROME,
        PASSED_ORIGINAL,
        PASSED_FALLBACK_FIREFOX,
        PASSED_FALLBACK_CHROME,
        PASSED_MANUAL_FIREFOX,
        PASSED_MANUAL_CHROME,
        FAILED,
    }

    data class Snapshot(val event: Event, val timeMillis: Long)

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, FILE).delete()
            File(context.filesDir, "$FILE.bak").delete()
        }
    }

    fun mark(context: Context, event: Event) {
        runCatching {
            val file = AtomicFile(File(context.filesDir, FILE))
            val payload = "${System.currentTimeMillis()}:${event.name}"
                .toByteArray(Charsets.US_ASCII)
            val stream = file.startWrite()
            try {
                stream.write(payload)
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    fun recent(context: Context): Snapshot? = runCatching {
        val file = AtomicFile(File(context.filesDir, FILE))
        if (!file.baseFile.exists() || file.baseFile.length() !in 1L..85L) {
            return@runCatching null
        }
        val encoded = file.openRead().bufferedReader(Charsets.US_ASCII).use { it.readText() }
        val timestamp = encoded.substringBefore(':').toLongOrNull()
            ?: return@runCatching null
        val state = runCatching { Event.valueOf(encoded.substringAfter(':')) }
            .getOrNull() ?: return@runCatching null
        val elapsed = System.currentTimeMillis() - timestamp
        if (elapsed !in 0..MAX_AGE_MS) return@runCatching null
        Snapshot(state, timestamp)
    }.getOrNull()
}
