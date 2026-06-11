package com.helios.subly.sdk

import android.util.Log

/**
 * Benchmark logging for the SDK pipeline (translation, sentence assembly).
 *
 * Uses the same `SublyBench` tag as the ASR layer so one logcat filter
 * captures the whole pipeline:
 * ```
 * adb logcat -s SublyBench
 * ```
 * Content lines (source/translated text) are privacy-sensitive and only
 * logged while VERBOSE is explicitly enabled:
 * ```
 * adb shell setprop log.tag.SublyBench VERBOSE
 * ```
 */
internal object BenchLog {
    const val TAG = "SublyBench"

    fun metric(line: String) {
        Log.i(TAG, line)
    }

    val transcriptEnabled: Boolean
        get() = Log.isLoggable(TAG, Log.VERBOSE)

    fun transcript(line: String) {
        if (transcriptEnabled) Log.v(TAG, line)
    }
}
