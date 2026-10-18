package com.helios.subly.core.model

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

/**
 * Remembers that a compute delegate took the process down during init, so
 * the next launch can skip it instead of crashing again.
 *
 * [arm] writes a marker before the risky init and [disarm] deletes it after.
 * A marker still present at the next [skipReason] means the process died in
 * between. Not every death is the delegate's fault, though: the low-memory
 * killer also ends processes mid-init, and skipping the GPU then would make
 * things worse, since the CPU path holds more native memory. So the marker
 * only counts when Android's record of the last exit is not a low-memory
 * kill.
 */
class DelegateCrashGuard(
    private val context: Context,
    private val marker: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    /** Null to try the delegate, or why it is being skipped. */
    fun skipReason(): String? {
        if (!marker.exists()) return null
        val diedAt = marker.lastModified()
        val ageMs = System.currentTimeMillis() - diedAt
        if (ageMs >= ttlMs) {
            marker.delete()
            return null
        }
        val exit = lastExitSince(diedAt)
        if (exit?.reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
            // Killed for memory, not by the delegate: try it again.
            marker.delete()
            return null
        }
        val why = exit?.let { describe(it.reason) } ?: "unknown exit"
        return "skipped: the process died ($why) during init on this delegate ${ageMs / 60_000} min ago"
    }

    fun arm(note: String) {
        marker.parentFile?.mkdirs()
        runCatching { marker.writeText(note) }
    }

    fun disarm() {
        marker.delete()
    }

    /**
     * The first exit after the marker was written: the death during init.
     * Later exits (an app update, a force stop) say nothing about the
     * delegate. The platform lists exits newest first.
     */
    private fun lastExitSince(sinceMs: Long): ApplicationExitInfo? = runCatching {
        context.getSystemService(ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            ?.lastOrNull { it.timestamp >= sinceMs }
    }.getOrNull()

    private fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        else -> "exit reason $reason"
    }

    companion object {
        /** A delegate that crashed the process is retried after a day. */
        const val DEFAULT_TTL_MS: Long = 24L * 60 * 60 * 1000
        private const val MAX_EXITS = 16
    }
}
