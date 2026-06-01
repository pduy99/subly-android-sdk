package com.helios.subly.asr.whisper

import android.content.Context
import android.util.Log
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import java.io.File
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class WhisperTranscriber(
    private val context: Context,
    private val backend: WhisperBackend = WhisperBackend.Jni,
    private val speechSegmenter: SpeechSegmenter = SpeechSegmenter(),
    private val modelDownloader: ModelDownloader = OkHttpModelDownloader(),
) : SublyAsr {

    private val handleRef = AtomicLong(0L)
    private val targetLangRef = AtomicReference("")
    private val nativeLock = Any()

    // -------------------------------------------------------------------------
    // Prepare
    // -------------------------------------------------------------------------

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)

        if (!backend.isAvailable()) {
            val error = IllegalStateException("Whisper JNI unavailable; prepare is a no-op.")
            Log.w(TAG, error.message!!)
            emit(ModelPrepState.Error(error))
            return@flow
        }

        targetLangRef.set(config.source.normalizeLangTag())

        if (handleRef.get() != 0L) {
            emit(ModelPrepState.Ready)
            return@flow
        }

        val modelPath = runCatching {
            ensureModelExtractedWithProgress { progress ->
                emit(ModelPrepState.Preparing(progress * 90f))
            }
        }.getOrElse {
            Log.e(TAG, "Failed to extract whisper model from assets", it)
            emit(ModelPrepState.Error(it))
            return@flow
        }

        emit(ModelPrepState.Preparing(90f))
        val created = backend.init(modelPath, targetLangRef.get())

        if (created == 0L) {
            Log.e(TAG, "whisper_init returned null for $modelPath")
            val error = IllegalStateException("whisper_init returned null for $modelPath")
            emit(ModelPrepState.Error(error))
            return@flow
        }

        if (!handleRef.compareAndSet(0L, created)) {
            runCatching { backend.release(created) }
        }

        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Transcribe
    // -------------------------------------------------------------------------

    override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> {
        if (!backend.isAvailable() || handleRef.get() == 0L) {
            return flow {
                throw IllegalStateException("Whisper engine not initialized. Call prepare() first.")
            }
        }

        val pcmWindows: Flow<FloatArray> = speechSegmenter.chunk(frames).transform { window ->
            checkSampleRate(window.sampleRateHz)
            emit(window.pcm)
        }

        return pcmWindows
            .buffer(capacity = 4, onBufferOverflow = BufferOverflow.SUSPEND)
            .transform { pcm ->
                Log.d(
                    TAG, "Whisper inference: ${pcm.size} samples " +
                            "(${pcm.size * 1000 / WHISPER_SAMPLE_RATE} ms)"
                )

                val text = runCatching {
                    synchronized(nativeLock) {
                        val h = handleRef.get()
                        if (h == 0L) "" else backend.transcribe(h, pcm, WHISPER_SAMPLE_RATE).trim()
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "Whisper native transcribe failed", e)
                    ""
                }

                if (text.isNotEmpty()) {
                    val lang = synchronized(nativeLock) {
                        val h = handleRef.get()
                        if (h != 0L) backend.lastDetectedLang(h) else ""
                    }
                    Log.d(TAG, "Whisper emitted text='$text' lang='$lang'")
                    emit(AsrResult.Final(text))
                }
            }
            .flowOn(Dispatchers.Default)
    }

    override fun release() {
        synchronized(nativeLock) {
            val h = handleRef.getAndSet(0L)
            if (h != 0L) runCatching { backend.release(h) }
        }
    }

    // -------------------------------------------------------------------------
    // Chunking helpers
    // -------------------------------------------------------------------------

    private fun checkSampleRate(actual: Int) {
        if (actual != WHISPER_SAMPLE_RATE) {
            Log.e(
                TAG, "SAMPLE RATE MISMATCH: audio is ${actual} Hz but Whisper requires " +
                        "$WHISPER_SAMPLE_RATE Hz. Set AudioRecord.SAMPLE_RATE = 16000 or " +
                        "add a resampler. Transcription will be empty/garbled."
            )
        }
    }

    // -------------------------------------------------------------------------
    // Model extraction
    // -------------------------------------------------------------------------

    private suspend fun ensureModelExtractedWithProgress(
        onProgress: suspend (Float) -> Unit,
    ): String {
        val outDir = File(context.filesDir, "whisper").apply { mkdirs() }
        val outFile = File(outDir, MODEL_ASSET)

        if (outFile.exists() && outFile.length() > 0L) {
            onProgress(1f)
            return outFile.absolutePath
        }

        val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${MODEL_ASSET}?download=true"
        modelDownloader.downloadModel(modelUrl, outFile).collect { progress ->
            onProgress(progress)
        }
        
        return outFile.absolutePath
    }

    private fun String.normalizeLangTag(): String =
        substringBefore('-').lowercase().takeIf { it.isNotBlank() && it != "auto" } ?: ""

    private companion object {
        const val TAG = "WhisperAsrEngine"
        const val MODEL_ASSET = "ggml-base.bin"
        const val WHISPER_SAMPLE_RATE = 16_000
    }
}