package com.helios.subly.asr.whisper

import android.util.Log

/**
 * Benchmark logging for the whisper ASR pipeline.
 *
 * Speed metrics are single `key=value` lines with no transcript content,
 * logged at INFO under [TAG] (the JNI layer logs under the same tag).
 * Capture the whole pipeline with:
 * ```
 * adb logcat -s SublyBench
 * ```
 *
 * Transcript content — needed for accuracy benchmarking (e.g. WER against a
 * reference script) but privacy-sensitive — is logged ONLY while VERBOSE is
 * explicitly enabled for the tag on the device:
 * ```
 * adb shell setprop log.tag.SublyBench VERBOSE   # enable (until reboot)
 * adb shell setprop log.tag.SublyBench INFO      # disable
 * ```
 */
internal object BenchLog {
    const val TAG = "SublyBench"

    fun metric(line: String) {
        Log.i(TAG, line)
    }

    /** True when transcript-content logging was explicitly opted into. */
    val transcriptEnabled: Boolean
        get() = Log.isLoggable(TAG, Log.VERBOSE)

    /** Logs end-user speech content; gated behind [transcriptEnabled]. */
    fun transcript(line: String) {
        if (transcriptEnabled) Log.v(TAG, line)
    }
}
