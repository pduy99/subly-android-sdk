package com.helios.subly.asr.sherpa

import android.util.Log
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps the sherpa-onnx OnlinePunctuation model.
 */
internal class SherpaOnnxPunctuation(private val modelDir: String) {

    private var punctuator: OnlinePunctuation? = null

    init {
        runCatching {
            val config = OnlinePunctuationConfig(
                model = OnlinePunctuationModelConfig(
                    cnnBilstm = "$modelDir/model.int8.onnx",
                    bpeVocab = "$modelDir/bpe.vocab",
                    numThreads = 1,
                    debug = false,
                    provider = "cpu"
                )
            )
            punctuator = OnlinePunctuation(assetManager = null, config = config)
        }
    }

    suspend fun punctuate(text: String): String = withContext(Dispatchers.Default) {
        val p = punctuator ?: return@withContext text
        val result = p.addPunctuation(text)
        Log.d("DUY", "Punctuation result: $result")
        result
    }

    fun release() {
        runCatching {
            punctuator?.release()
            punctuator = null
        }
    }
}
