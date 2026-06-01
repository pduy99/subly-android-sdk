package com.helios.subly.sdk

import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.audio.impl.AudioRecordDataSource
import com.helios.subly.core.data.repository.AudioCapture
import com.helios.subly.core.data.repository.AudioPlayback
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.translator.api.SublyTranslator

/**
 * Public entry point exposed by the Subly SDK.
 */
class Subly private constructor(
    private val sublyAsr: SublyAsr,
    private val translator: SublyTranslator,
    private val audioCapture: AudioCapture,
) {
    /**
     * Creates a stateful session for a specific language pair.
     */
    fun createSession(config: LanguageConfig): SublySession {
        return SublySession(config, audioCapture, sublyAsr, translator)
    }

    class Builder {
        private var sublyAsr: SublyAsr? = null
        private var translator: SublyTranslator? = null

        fun setAsrEngine(asr: SublyAsr) = apply { sublyAsr = asr }
        fun setTranslator(translator: SublyTranslator) = apply { this.translator = translator }

        fun build(): Subly = Subly(
            sublyAsr = sublyAsr!!,
            translator = translator!!,
            audioCapture = AudioPlayback(
                dataSource = AudioRecordDataSource()
            )
        )
    }
}