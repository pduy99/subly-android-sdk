package com.helios.subly.translator.api

import com.helios.subly.core.model.LanguageConfig
import kotlinx.coroutines.flow.Flow

interface TranslatorDataSource {

    suspend fun translate(text: String, languageConfig: LanguageConfig): String

    /**
     * Ensures the translation model for [languageConfig] is downloaded and
     * ready. Returns true if the model is available; false if preparation
     * failed but a graceful fallback is acceptable.
     */
    suspend fun ensureModel(languageConfig: LanguageConfig): Boolean

    /**
     * Prepares the translation model for [languageConfig] with observable progress.
     *
     * Emits progress values in the range [0.0, 1.0], then completes.
     * Throws on unrecoverable failure.
     */
    fun prepareModel(languageConfig: LanguageConfig): Flow<Float>
}
