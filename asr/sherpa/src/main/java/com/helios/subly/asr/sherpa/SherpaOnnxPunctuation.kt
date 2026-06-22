package com.helios.subly.asr.sherpa

import android.util.Log
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Wraps the sherpa-onnx OnlinePunctuation (CNN-BiLSTM) model.
 *
 * Construction loads the native model and **throws** on any failure (missing
 * files, bad model, native init error). It deliberately does NOT swallow:
 * a half-constructed instance whose internal model is null would silently
 * pass text through, which is indistinguishable from "punctuation is broken".
 * The caller is expected to construct this inside a `runCatching` and treat a
 * failure as "punctuation unavailable" (see [SherpaOnnxTranscriber]).
 */
internal class SherpaOnnxPunctuation(modelDir: String) {

    private val punctuator: OnlinePunctuation

    init {
        val model = File(modelDir, MODEL_FILE)
        val vocab = File(modelDir, VOCAB_FILE)
        check(model.isFile && model.length() > 0L) {
            "Punctuation model missing or empty: ${model.absolutePath}"
        }
        check(vocab.isFile && vocab.length() > 0L) {
            "Punctuation vocab missing or empty: ${vocab.absolutePath}"
        }

        val config = OnlinePunctuationConfig(
            model = OnlinePunctuationModelConfig(
                cnnBilstm = model.absolutePath,
                bpeVocab = vocab.absolutePath,
                numThreads = 1,
                debug = false,
                provider = "cpu",
            )
        )
        // Throws if the native side can't load the model — let it propagate.
        punctuator = OnlinePunctuation(assetManager = null, config = config)
        Log.i(TAG, "OnlinePunctuation loaded from $modelDir")
    }

    suspend fun punctuate(text: String): String = withContext(Dispatchers.Default) {
        // This is a punctuation + word-casing RESTORATION model: it expects
        // caseless input and predicts both casing and punctuation. The
        // streaming Zipformer emits ALL-CAPS tokens, which are out of the
        // model's training distribution — fed as-is it returns the text
        // unchanged (no punctuation, no casing). Normalize to lowercase first
        // so the model can do its job.
        // NOTE: never log the text or result — it is end-user speech.
        val normalized = text.lowercase(Locale.ENGLISH)
        runCatching { punctuator.addPunctuation(normalized) }
            .onFailure { Log.w(TAG, "addPunctuation failed; emitting raw text.", it) }
            .getOrDefault(text)
    }

    fun release() {
        runCatching { punctuator.release() }
    }

    private companion object {
        const val TAG = "SherpaOnnxPunctuation"
        const val MODEL_FILE = "model.int8.onnx"
        const val VOCAB_FILE = "bpe.vocab"
    }
}
