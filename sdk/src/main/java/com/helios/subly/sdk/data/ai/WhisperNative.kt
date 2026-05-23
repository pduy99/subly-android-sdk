package com.helios.subly.sdk.data.ai

import android.util.Log

/**
 * Thin JNI surface for `libwhisper_jni.so`. Signatures mirror
 * `sdk/src/main/cpp/whisper_jni.cpp` one-for-one; do not rename without
 * regenerating the C++ side.
 *
 * `nativeIsAvailable()` is a runtime probe rather than a `try/catch` on
 * each call site: it returns `false` when the library was built in stub
 * mode (no whisper.cpp vendored) so the Kotlin layer can degrade once,
 * up front, instead of paying JNI cost per frame.
 */
internal object WhisperNative {

    private const val TAG = "SublyWhisperNative"

    /** Lazy-load; `false` means the .so was missing or stub-built. */
    val isLoaded: Boolean by lazy {
        val ok = runCatching { System.loadLibrary("whisper_jni") }.isSuccess
        if (!ok) Log.w(TAG, "libwhisper_jni.so not found; AI core disabled.")
        ok
    }

    /** True only when the .so is loaded AND built with `SUBLY_HAS_WHISPER`. */
    fun isAvailable(): Boolean = isLoaded && nativeIsAvailable()

    /** Returns a context handle (>0) or 0 on failure. */
    external fun nativeInit(modelPath: String, targetLanguageCode: String): Long

    /** Runs full inference over one PCM window; returns concatenated text. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, sampleRate: Int): String

    external fun nativeRelease(handle: Long)

    private external fun nativeIsAvailable(): Boolean
}
