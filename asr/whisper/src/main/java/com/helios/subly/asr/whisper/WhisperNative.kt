package com.helios.subly.asr.whisper

import android.util.Log

/**
 * JNI bridge to `libwhisper_jni.so` (whisper.cpp).
 *
 * All `nativeXxx` methods must be called while holding the same monitor
 * (see [WhisperTranscriber.nativeLock]) — whisper.cpp inference and free
 * are not safe to interleave.
 */
internal object WhisperNative {

    private const val TAG = "SublyWhisperNative"

    /** Lazy-load; `false` means the .so was missing or stub-built. */
    val isLoaded: Boolean by lazy {
        val ok = runCatching { System.loadLibrary("whisper_jni") }.isSuccess
        if (!ok) Log.w(TAG, "libwhisper_jni.so not found; whisper ASR disabled.")
        ok
    }

    /** True only when the .so is loaded AND the JNI symbols are present. */
    fun isAvailable(): Boolean = isLoaded && runCatching { nativeIsAvailable() }.getOrDefault(false)

    /**
     * Returns a context handle (>0) or 0 on failure.
     *
     * [vadModelPath] points at a ggml Silero VAD model; pass an empty string
     * to disable the pre-transcribe speech gate. A failed VAD load is
     * non-fatal (the gate is simply skipped).
     */
    external fun nativeInit(
        modelPath: String,
        targetLanguageCode: String,
        vadModelPath: String,
    ): Long

    /**
     * Runs full inference over one PCM window; returns concatenated text.
     *
     * @param gateWithVad when true, runs the Silero VAD speech gate before
     *   decoding (used for final windows). Partial windows pass `false` to skip
     *   the per-call full-window VAD scan — see [WhisperTranscriber].
     */
    external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        sampleRate: Int,
        gateWithVad: Boolean,
    ): String

    /**
     * BCP-47 tag whisper detected on the most recent [nativeTranscribe]
     * call (e.g. "en", "vi"). Empty string if no call has succeeded yet
     * or whisper couldn't identify a language.
     */
    external fun nativeLastDetectedLang(handle: Long): String

    external fun nativeRelease(handle: Long)

    private external fun nativeIsAvailable(): Boolean
}
