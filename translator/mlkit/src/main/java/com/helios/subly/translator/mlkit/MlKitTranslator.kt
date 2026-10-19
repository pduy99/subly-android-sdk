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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

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

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        try {
            emit(ModelPrepState.Checking)

            val sourceTag = TranslateLanguage.fromLanguageTag(config.source)
            val targetTag = TranslateLanguage.fromLanguageTag(config.target)

            if (sourceTag == null || targetTag == null) {
                throw IllegalArgumentException(
                    "Unsupported language pair: '${config.source}' -> " +
                            "'${config.target}'. See TranslateLanguage for supported tags."
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

            // Bounded: a download that never completes (seen on a Galaxy
            // S24 Ultra, Oct 2026: no progress and no error for 20+ min)
            // must surface as an error the user can retry, not an endless
            // "Preparing".
            val downloaded = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                activeTranslator?.downloadModelIfNeeded(downloadConditions)?.await()
                true
            }
            if (downloaded == null) {
                emit(ModelPrepState.Error(TimeoutException(
                    "ML Kit model download for ${config.source}->${config.target} " +
                        "did not finish in ${DOWNLOAD_TIMEOUT_MS / 60_000} min"
                )))
                return@flow
            }

            emit(ModelPrepState.Ready)
        } catch (e: CancellationException) {
            // Terminal operators (`first {}`, `take()`) stop a flow by throwing
            // AbortFlowException, a CancellationException. Catching it and
            // emitting again violates flow exception transparency and crashes
            // the collector, so cancellation must pass straight through.
            throw e
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

        /** A language pair is two ~30 MB models; minutes even on a slow link. */
        const val DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1_000L
    }
}