package com.helios.subly.sdk.data.ai

import android.util.Log
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig

/**
 * Wraps the sherpa-onnx OnlinePunctuation model.
 */
internal class SherpaOnnxPunctuation(private val modelDir: String) {

    private var punctuator: OnlinePunctuation? = null

    fun init(): Boolean {
        if (punctuator != null) return true

        return runCatching {
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
            true
        }.onFailure { error ->
            Log.e("SherpaOnnxPunctuation", "Failed to init punctuation: ${error.message}", error)
        }.getOrDefault(false)
    }

    fun punctuate(packet: TranslationPacket): TranslationPacket {
        val p = punctuator ?: return packet
        return packet.copy(text = runCatching { p.addPunctuation(packet.text) }.getOrDefault(packet.text))
    }

    fun release() {
        runCatching {
            punctuator?.release()
            punctuator = null
        }
    }
}
