package com.helios.subly.asr.api

import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.TranslationPacket
import kotlinx.coroutines.flow.Flow

interface AsrDataSource {

    fun transcribe(frames: Flow<AudioFrame>): Flow<TranslationPacket>

    /**
     * Prepares the ASR model for [languageConfig].
     *
     * Emits progress values in the range [0.0, 1.0] as the model is extracted
     * or downloaded, then completes. Callers should collect this flow to
     * completion before starting the transcription pipeline.
     */
    fun prepareModel(languageConfig: LanguageConfig): Flow<Float>

    fun release()
}
