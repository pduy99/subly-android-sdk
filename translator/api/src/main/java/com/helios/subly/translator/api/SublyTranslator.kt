package com.helios.subly.translator.api

import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.flow.Flow

interface SublyTranslator {

    suspend fun translate(text: String): String

    fun prepareModel(languageConfig: LanguageConfig): Flow<ModelPrepState>

    fun release()
}
