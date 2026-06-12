package com.helios.subly.translator.mlkit

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

/**
 * [SublyTranslator] backed by ML Kit on-device translation.
 *
 * @param downloadConditions conditions for the (potentially large) model
 * download. Defaults to unrestricted; pass
 * `DownloadConditions.Builder().requireWifi().build()` to avoid downloading
 * over metered connections.
 */
class MlKitTranslator(
    private val downloadConditions: DownloadConditions = DownloadConditions.Builder().build(),
) : SublyTranslator {

    private var activeTranslator: Translator? = null

    override suspend fun translate(text: String): String {
        val translator = activeTranslator
            ?: throw IllegalStateException("Translator not prepared. Call prepareModel first.")

        return try {
            translator.translate(text).await()
            // NOTE: never log `text` or the result — it is end-user speech.
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed (input length=${text.length})", e)
            throw e
        }
    }

    override fun prepareModel(languageConfig: LanguageConfig): Flow<ModelPrepState> = flow {
        try {
            emit(ModelPrepState.Checking)

            val sourceTag = TranslateLanguage.fromLanguageTag(languageConfig.source)
            val targetTag = TranslateLanguage.fromLanguageTag(languageConfig.target)

            if (sourceTag == null || targetTag == null) {
                throw IllegalArgumentException(
                    "Unsupported language pair: '${languageConfig.source}' -> " +
                            "'${languageConfig.target}'. See TranslateLanguage for supported tags."
                )
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()

            // Close any previously active translator to free up memory.
            activeTranslator?.close()
            activeTranslator = Translation.getClient(options)

            // ML Kit doesn't expose download progress: this phase is
            // indeterminate, reported as a constant 0f per the
            // ModelPrepState contract.
            emit(ModelPrepState.Preparing(progress = 0f))

            activeTranslator?.downloadModelIfNeeded(downloadConditions)?.await()

            emit(ModelPrepState.Ready)
        } catch (e: Exception) {
            emit(ModelPrepState.Error(e))
        }
    }

    override fun release() {
        activeTranslator?.close()
        activeTranslator = null
    }

    override fun supportedLanguages(): List<String> {
        return TranslateLanguage.getAllLanguages().toList()
    }

    private companion object {
        const val TAG = "MlKitTranslator"
    }
}