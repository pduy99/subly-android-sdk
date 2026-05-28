package com.helios.subly.asr.whisper

import android.content.Context
import android.util.Log
import com.helios.subly.asr.api.AsrDataSource
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.TranslationPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [AsrDataSource] backed by quantized whisper.cpp via JNI.
 *
 * Lifecycle:
 *  - [prepareModel] extracts [MODEL_ASSET] from APK assets into `filesDir`
 *    on first call, then opens a native whisper context bound to the
 *    requested target language.
 *  - [transcribe] runs VAD-segmented inference per utterance and emits a
 *    final [TranslationPacket] per segment. Text is in the *source*
 *    language whisper detected; downstream NMT handles translation.
 *  - If the native lib is stub-built (or the model is missing), it
 *    degrades to a silent drain that still preserves backpressure.
 */
class WhisperTranscriber(
    private val context: Context,
    private val segmenter: SpeechSegmenter = SpeechSegmenter(),
    private val backend: WhisperBackend = WhisperBackend.Jni,
) : AsrDataSource {

    private val handleRef = AtomicLong(0L)
    private val targetLangRef = AtomicReference("")

    /**
     * Serializes JNI `transcribe` and `release` so the native context is
     * never freed while an inference is still running on a worker thread.
     * Whisper.cpp inference is a non-cancellable blocking JNI call —
     * without this lock, stop() racing a chunk causes a SIGSEGV in
     * libggml-cpu.so.
     */
    private val nativeLock = Any()

    /**
     * Extracts the model from APK assets (if needed) then initializes the
     * native backend. Emits extraction progress [0.0, 0.9], then 1.0 once
     * the native context is ready.
     */
    override fun prepareModel(languageConfig: LanguageConfig): Flow<Float> = flow {
        if (!backend.isAvailable()) {
            Log.w(TAG, "Whisper JNI unavailable; prepareModel is a no-op.")
            emit(1f)
            return@flow
        }
        targetLangRef.set(languageConfig.source.normalizeLangTag())
        if (handleRef.get() != 0L) {
            emit(1f)
            return@flow
        }

        // Extraction phase: 0% -> 90%
        val modelPath = runCatching {
            ensureModelExtractedWithProgress { progress -> emit(progress * 0.9f) }
        }.getOrElse {
            Log.e(TAG, "Failed to extract whisper model from assets", it)
            return@flow
        }

        // Native init phase: 90% -> 100%
        emit(0.9f)
        val created = backend.init(modelPath, targetLangRef.get())
        if (created == 0L) {
            Log.e(TAG, "whisper_init returned null for $modelPath")
            return@flow
        }
        if (!handleRef.compareAndSet(0L, created)) {
            runCatching { backend.release(created) }
        }
        emit(1f)
    }.flowOn(Dispatchers.IO)

    override fun transcribe(frames: Flow<AudioFrame>): Flow<TranslationPacket> {
        if (!backend.isAvailable() || handleRef.get() == 0L) {
            throw IllegalStateException("Whisper transcribe skipped frame")
//            return frames.transform {
//                Log.w(TAG, "Whisper transcribe skipped frame")
//            /* drain, preserve backpressure */
//            }
        }

        return segmenter.chunk(frames)
            .transform { window ->
                val (text, detectedLang) = runCatching {
                    synchronized(nativeLock) {
                        val h = handleRef.get()
                        if (h == 0L) {
                            "" to ""
                        } else {
                            val t = backend.transcribe(h, window.pcm, window.sampleRateHz)
                            t to backend.lastDetectedLang(h)
                        }
                    }
                }.getOrElse {
                    Log.w(TAG, "Whisper transcribe failed", it)
                    "" to ""
                }.let { (t, l) -> t.trim() to l }

                if (text.isNotEmpty()) {
                    Log.d(TAG, "Whisper transcribe text='$text' lang='$detectedLang'")
                    emit(
                        TranslationPacket(
                            text = text,
                            sourceLanguageCode = detectedLang.ifEmpty { targetLangRef.get().ifEmpty { "auto" } },
                            targetLanguageCode = null,
                            timestampMs = window.startTimestampMs,
                            source = TranslationPacket.Source.AUDIO,
                            isFinal = true,
                        ),
                    )
                }
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
    }

    /**
     * Copies [MODEL_ASSET] from assets into `filesDir/whisper/` on first
     * call, reporting byte-level progress via [onProgress]. Subsequent calls
     * return the cached path immediately. Whisper.cpp requires a real
     * filesystem path; loading from assets directly needs an AAssetManager +
     * custom model_loader which we skip for now.
     */
    private suspend fun ensureModelExtractedWithProgress(onProgress: suspend (Float) -> Unit): String {
        val outDir = File(context.filesDir, "whisper").apply { mkdirs() }
        val outFile = File(outDir, MODEL_ASSET)
        if (outFile.exists() && outFile.length() > 0L) {
            onProgress(1f)
            return outFile.absolutePath
        }

        val totalBytes = runCatching {
            context.assets.openFd(MODEL_ASSET).declaredLength
        }.getOrDefault(1L).coerceAtLeast(1L)

        context.assets.open(MODEL_ASSET).use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copiedBytes = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copiedBytes += read
                    onProgress((copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
            }
        }
        return outFile.absolutePath
    }

    /** Normalize "en-US" -> "en". Whisper expects bare ISO-639-1 tags. */
    private fun String.normalizeLangTag(): String =
        substringBefore('-').lowercase().takeIf { it.isNotBlank() && it != "auto" } ?: ""

    private companion object {
        const val TAG = "SublyWhisperTranscriber"
        const val MODEL_ASSET = "ggml-base-q5_1.bin"
    }
}
