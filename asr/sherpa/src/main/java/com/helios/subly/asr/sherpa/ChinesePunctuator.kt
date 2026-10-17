package com.helios.subly.asr.sherpa

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Restores punctuation in Chinese recogniser output ([SherpaOnnxModel.PUNCT_ZH_EN]).
 *
 * Owned by a transcriber: provisioned during its prepare, applied to its
 * finals, released with it. Failure to provision or load is not fatal: the
 * transcriber then emits unpunctuated finals, as before.
 */
internal class ChinesePunctuator(
    private val context: Context,
    private val modelLoaderFactory: SherpaOnnxModelLoaderFactory,
) {
    private val lock = Any()
    private var punctuation: OfflinePunctuation? = null

    val isReady: Boolean get() = synchronized(lock) { punctuation != null }

    /** Downloads (first time) and loads the model, emitting progress 0..1. */
    fun prepare(): Flow<Float> = flow {
        if (isReady) {
            emit(1f)
            return@flow
        }
        val loader = modelLoaderFactory.create(context)
        loader.provisionWithProgress(SherpaOnnxModel.PUNCT_ZH_EN).collect { emit(it) }
        if (!loader.isReady(SherpaOnnxModel.PUNCT_ZH_EN)) {
            Log.w(TAG, "Punctuation model unavailable; finals stay unpunctuated.")
            return@flow
        }
        val model = loader.downloadTarget(SherpaOnnxModel.PUNCT_ZH_EN).listFiles()
            ?.firstOrNull { it.name.endsWith(".onnx") } ?: return@flow
        val created = runCatching {
            OfflinePunctuation(
                assetManager = null,
                config = OfflinePunctuationConfig(
                    model = OfflinePunctuationModelConfig(ctTransformer = model.absolutePath, numThreads = 1),
                ),
            )
        }.onFailure { Log.w(TAG, "Punctuation model failed to load", it) }.getOrNull()
        synchronized(lock) {
            if (punctuation == null) punctuation = created else created?.release()
        }
        emit(1f)
    }

    /** [text] with punctuation restored, or unchanged when not loaded. */
    fun punctuate(text: String): String {
        if (text.isBlank()) return text
        return synchronized(lock) {
            val live = punctuation ?: return text
            runCatching { live.addPunctuation(text) }.getOrDefault(text)
        }
    }

    fun release() {
        synchronized(lock) {
            punctuation?.release()
            punctuation = null
        }
    }

    private companion object {
        const val TAG = "ChinesePunctuator"
    }
}
