package com.helios.subly.asr.vosk

import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Test seam over the Vosk (`org.vosk.*`) Java API.
 *
 * Mirrors the role of `SherpaOnnxBackend`: lets unit tests drive
 * [VoskTranscriber] without loading `libvosk.so` (which JVM unit tests can't do
 * — it requires an instrumentation test). The real implementation is
 * [JniVoskBackend].
 */
internal interface VoskBackend {

    /**
     * True when the Vosk native library is loadable on this device. `false`
     * usually means `libvosk.so` is missing for the current ABI (e.g. an x86
     * emulator while the SDK is arm64-v8a-only) — the transcriber then fails
     * loudly rather than silently draining audio.
     */
    fun isAvailable(): Boolean

    /** Opens the model dir and creates a recognizer, or `null` on failure. */
    fun init(modelDir: String, sampleRateHz: Int): Handle?

    /**
     * Feeds mono 16-bit PCM at the recognizer's sample rate. Returns `true` when
     * Vosk reports an utterance endpoint (silence boundary) — the caller should
     * then read [finalText]; otherwise it reads [partialText].
     */
    fun acceptWaveform(handle: Handle, pcm: ShortArray, len: Int): Boolean

    /** Final hypothesis for the just-ended utterance (parsed `text` field). */
    fun finalText(handle: Handle): String

    /** Current in-progress hypothesis (parsed `partial` field). */
    fun partialText(handle: Handle): String

    /** Flush + return any buffered final text (parsed `text`), then reset. */
    fun flushFinalText(handle: Handle): String

    /** Discard in-flight audio so the next utterance starts clean. */
    fun reset(handle: Handle)

    /** Release recognizer + model native resources. Idempotent. */
    fun release(handle: Handle)

    /** Opaque holder for the Vosk model + recognizer pair. */
    class Handle internal constructor(
        internal val model: Model,
        internal val recognizer: Recognizer,
    )

    companion object {
        /** Default real-recognizer-backed implementation. */
        val Jni: VoskBackend = JniVoskBackend
    }
}

/**
 * Real [VoskBackend] backed by the `org.vosk` bindings from the
 * `com.alphacephei:vosk-android` AAR.
 *
 * Availability probe lazily forces the JNI load once; on failure (wrong ABI,
 * missing .so) [isAvailable] returns `false` permanently and the transcriber
 * degrades instead of crashing.
 */
private object JniVoskBackend : VoskBackend {

    private const val TAG = "VoskBackend"

    private val available: Boolean by lazy {
        runCatching {
            // Touching LibVosk forces System.loadLibrary("vosk") via JNA.
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Vosk JNI unavailable: ${error.message}")
            false
        }
    }

    override fun isAvailable(): Boolean = available

    override fun init(modelDir: String, sampleRateHz: Int): VoskBackend.Handle? {
        if (!available) return null
        return runCatching {
            val model = Model(modelDir)
            val recognizer = Recognizer(model, sampleRateHz.toFloat())
            VoskBackend.Handle(model, recognizer)
        }.getOrElse { error ->
            Log.w(TAG, "Vosk init failed for $modelDir: ${error.message}")
            null
        }
    }

    override fun acceptWaveform(handle: VoskBackend.Handle, pcm: ShortArray, len: Int): Boolean =
        runCatching { handle.recognizer.acceptWaveForm(pcm, len) }.getOrDefault(false)

    override fun finalText(handle: VoskBackend.Handle): String =
        parse(runCatching { handle.recognizer.result }.getOrNull(), "text")

    override fun partialText(handle: VoskBackend.Handle): String =
        parse(runCatching { handle.recognizer.partialResult }.getOrNull(), "partial")

    override fun flushFinalText(handle: VoskBackend.Handle): String =
        parse(runCatching { handle.recognizer.finalResult }.getOrNull(), "text")

    override fun reset(handle: VoskBackend.Handle) {
        runCatching { handle.recognizer.reset() }
    }

    override fun release(handle: VoskBackend.Handle) {
        // Order matters: close the recognizer (which references the model)
        // before the model. Both swallow exceptions — double-close is harmless
        // and we don't want stop()/close() to surface shutdown noise.
        runCatching { handle.recognizer.close() }
        runCatching { handle.model.close() }
    }

    /** Pulls [key] out of a Vosk JSON result string; "" on any problem. */
    private fun parse(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(key, "") }.getOrDefault("")
    }
}
