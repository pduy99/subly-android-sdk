package com.helios.subly.translator.api

import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.flow.Flow

/**
 * A text translation engine.
 *
 * Implementations are *session-scoped*: each [com.helios.subly.sdk.SublySession]
 * owns exactly one instance and is responsible for calling [release].
 */
interface SublyTranslator {

    /**
     * Translates [text] from the source to the target language configured via
     * the last successful [prepareModel].
     *
     * ### Contract
     * - MUST only be called after [prepareModel] has reached
     *   [ModelPrepState.Ready]; throws [IllegalStateException] otherwise.
     * - Runtime failures throw; the session maps them to a typed
     *   [com.helios.subly.core.model.SublyError.TranslationFailed].
     * - MUST NOT log the content of [text] or the result in release builds
     *   (it is end-user speech).
     */
    suspend fun translate(text: String): String

    /**
     * Prepares the translation model for [config].
     *
     * Cold flow. See the contract on [ModelPrepState]: must terminate with
     * [ModelPrepState.Ready] or [ModelPrepState.Error], must not throw, must
     * report progress normalized to `0f..1f`, and must be re-collectable.
     */
    fun prepareModel(config: LanguageConfig): Flow<ModelPrepState>

    /** Releases resources. Idempotent. */
    fun release()

    /**
     * Returns the list of supported BCP-47 language tags for translation.
     */
    fun supportedLanguages(): List<String>
}