package com.helios.subly.sdk.data.ai

import android.content.Context
import android.util.Log
import com.helios.subly.sdk.data.vision.OcrRecognizer
import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.model.VisionFrame
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import java.util.concurrent.atomic.AtomicLong

/**
 * [AiTranscriberRepository] backed by quantized Whisper.cpp via JNI.
 *
 * Lifecycle:
 *  - First [transcribeAudio] call lazily resolves the model file (assets ->
 *    filesDir unpack, or filesDir if previously downloaded) and calls
 *    [WhisperBackend.init]. The native handle is held until [release].
 *  - If the native lib is stub-built (or the model is missing), the
 *    transcriber degrades to a drain: it consumes upstream frames but emits
 *    no packets. This preserves backpressure and lets the engine come up on
 *    devices/CI without the AI core.
 *
 * Translation responsibility (Phase 3b):
 * Whisper here ONLY produces source-language text. The downstream
 * `TranslatePacketUseCase` runs the ML Kit NMT step to reach the requested
 * target. Emitted packets carry the BCP-47 source language whisper
 * detected (via `whisper_full_lang_id`); the use case falls back to ML
 * Kit Lang-ID only when whisper couldn't identify.
 */
internal class WhisperTranscriber(
    private val context: Context,
    private val model: WhisperModel = WhisperModel.Default,
    private val segmenter: SpeechSegmenter = SpeechSegmenter(),
    private val backend: WhisperBackend = WhisperBackend.Jni,
    private val modelLoaderFactory: WhisperModelLoaderFactory = WhisperModelLoaderFactory.Default,
    private val ocrRecognizer: OcrRecognizer? = null,
) : AiTranscriberRepository {

    private val handleRef = AtomicLong(0L)

    /**
     * Serializes JNI `transcribe` and `release` so the native context is
     * never freed while an inference is still running on a worker thread.
     * Whisper.cpp / GGML inference is non-cancellable from Kotlin (it's a
     * blocking JNI call), so coroutine cancellation can't interrupt it —
     * without this lock, `stop()` racing a chunk inference causes a
     * SIGSEGV in libggml-cpu.so.
     */
    private val nativeLock = Any()

    override fun transcribeAudio(
        frames: Flow<AudioFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> {
        if (!backend.isAvailable()) {
            return frames.transform { /* drain, preserve backpressure */ }
        }

        val handle = ensureHandle(config.targetLanguageCode)
        if (handle == 0L) {
            return frames.transform { /* drain */ }
        }

        var windowCount = 0
        return segmenter.chunk(frames)
            .transform { window ->
                windowCount++
                // Pair (text, detectedLang) is captured under the same lock
                // so the language matches the transcript on the JNI side.
                val (text, detectedLang) = runCatching {
                    synchronized(nativeLock) {
                        val h = handleRef.get()
                        if (h == 0L) "" to "" else {
                            val t = backend.transcribe(h, window.pcm, window.sampleRateHz)
                            t to backend.lastDetectedLang(h)
                        }
                    }
                }.getOrElse {
                    "" to ""
                }.let { (t, l) -> t.trim() to l }

                if (text.isNotEmpty()) {
                    emit(
                        TranslationPacket(
                            text = text,
                            // Whisper-detected language ("en", "vi", ...). Falls
                            // back to "auto" if whisper couldn't identify; the
                            // NMT stage will run ML Kit Lang-ID as a backstop.
                            sourceLanguageCode = detectedLang.ifEmpty { "auto" },
                            targetLanguageCode = config.targetLanguageCode,
                            timestampMs = window.startTimestampMs,
                            source = TranslationPacket.Source.AUDIO,
                            isFinal = true,
                        ),
                    )
                }
            }
            .flowOn(Dispatchers.Default)
    }

    override fun recognizeVision(
        frames: Flow<VisionFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> {
        val ocr = ocrRecognizer ?: run {
            Log.w(TAG, "No OCR recognizer wired; draining vision frames.")
            return frames.transform { /* drain */ }
        }
        // Suppress duplicate emissions (OCR often returns the same caption
        // across consecutive frames at 2 fps).
        var lastText = ""
        return frames
            .transform { frame ->
                val text = ocr.recognize(frame)
                if (text.isEmpty() || text == lastText) return@transform
                lastText = text
                emit(
                    TranslationPacket(
                        text = text,
                        // OCR output language is unknown; the downstream NMT
                        // stage runs language-id and decides translate vs.
                        // pass-through.
                        sourceLanguageCode = "auto",
                        targetLanguageCode = config.targetLanguageCode,
                        timestampMs = frame.timestampMs,
                        source = TranslationPacket.Source.VISION,
                        isFinal = true,
                    ),
                )
            }
            .flowOn(Dispatchers.Default)
    }

    override fun release() {
        // Hold nativeLock so we wait for any in-flight transcribe() to
        // complete before freeing the context. Without this, a stop()
        // race against an active chunk causes a SIGSEGV in GGML.
        synchronized(nativeLock) {
            val h = handleRef.getAndSet(0L)
            if (h != 0L) runCatching { backend.release(h) }
        }
        runCatching { ocrRecognizer?.close() }
    }

    private fun ensureHandle(targetLanguageCode: String): Long {
        val existing = handleRef.get()
        if (existing != 0L) {
            return existing
        }
        val modelPath = modelLoaderFactory.create(context).resolve(model) ?: run {
            return 0L
        }
        val created = backend.init(modelPath, targetLanguageCode)
        return if (handleRef.compareAndSet(0L, created)) {
            created
        } else {
            if (created != 0L) runCatching { backend.release(created) }
            handleRef.get()
        }
    }

    private companion object {
        const val TAG = "SublyWhisperTranscriber"
    }
}
