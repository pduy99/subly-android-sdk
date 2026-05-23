package com.helios.subly.sdk.data.ai

import android.content.Context
import android.util.Log
import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.model.VisionFrame
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * target. Emitted packets carry `sourceLanguageCode = "auto"` until the JNI
 * surfaces `whisper_full_lang_id`; the use case fills in the real source
 * via its own language identifier as a fallback.
 */
internal class WhisperTranscriber(
    private val context: Context,
    private val model: WhisperModel = WhisperModel.Default,
    private val chunker: AudioChunker = AudioChunker(),
    private val backend: WhisperBackend = WhisperBackend.Jni,
    private val modelLoaderFactory: (Context) -> WhisperModelLoader = ::WhisperModelLoader,
) : AiTranscriberRepository {

    private val handleRef = AtomicLong(0L)

    override fun transcribeAudio(
        frames: Flow<AudioFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> {
        if (!backend.isAvailable()) {
            Log.w(TAG, "Whisper backend unavailable; draining audio without transcription.")
            return frames.transform { /* drain, preserve backpressure */ }
        }

        val handle = ensureHandle(config.targetLanguageCode)
        if (handle == 0L) {
            Log.w(TAG, "Whisper init failed; draining audio without transcription.")
            return frames.transform { /* drain */ }
        }

        return chunker.chunk(frames)
            .transform { window ->
                val text = runCatching {
                    backend.transcribe(handle, window.pcm, window.sampleRateHz)
                }.getOrElse {
                    Log.e(TAG, "transcribe() failed", it)
                    ""
                }.trim()

                if (text.isNotEmpty()) {
                    emit(
                        TranslationPacket(
                            text = text,
                            // "auto" = source detected by Whisper but not yet
                            // surfaced; downstream NMT will identify if needed.
                            sourceLanguageCode = "auto",
                            // We forward the requested target so the NMT stage
                            // can decide whether to translate or pass-through.
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
    ): Flow<TranslationPacket> = emptyFlow() // Phase 4

    override fun release() {
        val h = handleRef.getAndSet(0L)
        if (h != 0L) runCatching { backend.release(h) }
    }

    private fun ensureHandle(targetLanguageCode: String): Long {
        val existing = handleRef.get()
        if (existing != 0L) return existing
        val modelPath = modelLoaderFactory(context).resolve(model) ?: run {
            Log.w(TAG, "No Whisper model on disk (looked for ${model.assetName}).")
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
