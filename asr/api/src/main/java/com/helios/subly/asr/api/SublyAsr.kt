package com.helios.subly.asr.api

import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.flow.Flow

interface SublyAsr {

    fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult>

    fun prepareModel(config: LanguageConfig): Flow<ModelPrepState>

    fun release()
}
