package com.helios.subly.translator.mlkit

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

class MlKitTranslator : SublyTranslator {

    private var activeTranslator: Translator? = null

    override suspend fun translate(text: String): String {
        val translator = activeTranslator
            ?: throw IllegalStateException("Translator not prepared. Call prepareModel first.")

        return try {
            val result = translator.translate(text).await()
            Log.d("MLKitTranslator", "Translation successful from $text: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            throw e
        }
    }

    override fun prepareModel(languageConfig: LanguageConfig): Flow<ModelPrepState> = flow {
        try {
            emit(ModelPrepState.Checking)

            val sourceTag = TranslateLanguage.fromLanguageTag(languageConfig.source)
            val targetTag = TranslateLanguage.fromLanguageTag(languageConfig.target)

            if (sourceTag == null || targetTag == null) {
                throw IllegalArgumentException("Invalid language codes provided.")
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceTag)
                .setTargetLanguage(targetTag)
                .build()

            // Close any previously active translator to free up memory
            activeTranslator?.close()
            activeTranslator = Translation.getClient(options)

            val conditions = DownloadConditions.Builder()
                .build()

            emit(ModelPrepState.Preparing(progress = 0f))

            activeTranslator?.downloadModelIfNeeded(conditions)?.await()

            emit(ModelPrepState.Ready)

        } catch (e: Exception) {
            emit(ModelPrepState.Error(e))
        }
    }

    override fun release() {
        activeTranslator?.close()
        activeTranslator = null
    }

    companion object {
        private val TAG = MlKitTranslator::class.simpleName
    }
}
