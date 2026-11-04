package io.github.romanvht.byedpi.services

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Android 11+ process-exit classification.
 *
 * Only the OS-provided reason is read: no tombstones, stack traces, process
 * descriptions, VPN profiles, network addresses, or credentials are copied
 * into the app diagnostic surface.
 *
 * A stage marker by itself does not prove a native Go panic: it also remains
 * when Android kills :native or a readiness check exits abruptly.
 */
internal object NativeExitInspector {
    private const val MAX_AGE_MS = 120_000L

    fun reason(context: Context, expectedPid: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val wantedName = "${context.packageName}:native"
            val records = activity.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            val now = System.currentTimeMillis()
            val record = records.firstOrNull {
                it.processName == wantedName &&
                    (expectedPid <= 0 || it.pid == expectedPid) &&
                    now - it.timestamp in 0L..MAX_AGE_MS
            } ?: return@runCatching null
            when (record.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALLED"
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "STOPPED_BY_OS"
                else -> "UNKNOWN_EXIT"
            }
        }.getOrNull()
    }
}
