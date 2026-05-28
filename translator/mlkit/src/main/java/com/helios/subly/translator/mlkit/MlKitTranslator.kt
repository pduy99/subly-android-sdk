package com.helios.subly.translator.mlkit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.translator.api.TranslatorDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Fast draft translator using ML Kit's on-device NMT models.
 */
class MlKitTranslator internal constructor(
    @Suppress("unused") private val languageIdentifier: LanguageIdentifier,
) : TranslatorDataSource {

    constructor() : this(languageIdentifier = LanguageIdentifierImpl())

    var modelLoaded = false

    private lateinit var translator: Translator

    override suspend fun translate(
        text: String,
        languageConfig: LanguageConfig,
    ): String = withContext(Dispatchers.Default) {
        if (!modelLoaded) {
            if (!ensureModel(languageConfig)) return@withContext text
        }

        val translated = try {
            translator.translate(text).await()
        } catch (_: Exception) {
            text
        }
        return@withContext translated
    }

    /**
     * Downloads and initializes the ML Kit translation model for [languageConfig].
     *
     * ML Kit's download Task does not expose intermediate progress, so this
     * flow emits 0.0 at the start and 1.0 on successful completion.
     * Throws on failure.
     */
    override fun prepareModel(languageConfig: LanguageConfig): Flow<Float> = flow {
        emit(0f)
        val success = ensureModel(languageConfig)
        if (!success) throw IllegalStateException("Failed to prepare translation model for $languageConfig")
        emit(1f)
    }

    override suspend fun ensureModel(languageConfig: LanguageConfig): Boolean {
        val src = TranslateLanguage.fromLanguageTag(languageConfig.source)
        val tgt = TranslateLanguage.fromLanguageTag(languageConfig.target)

        if (src == null || tgt == null) {
            return false
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(tgt)
            .build()
        translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        return try {
            translator.downloadModelIfNeeded(conditions).await()
            modelLoaded = true
            true
        } catch (_: Exception) {
            modelLoaded = false
            false
        }
    }
}
