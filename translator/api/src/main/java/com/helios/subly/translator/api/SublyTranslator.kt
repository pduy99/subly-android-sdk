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
     * Whether partial (in-progress) captions should be routed through this
     * engine.
     *
     * Partials arrive roughly every 200 ms and are re-translated as the
     * clause grows, so the cost of one translation is paid many times per
     * sentence. A per-pair NMT model absorbs that; an on-device LLM does not,
     * and because `SublySession` translates on the coroutine that collects
     * ASR results, a multi-second call there does not merely lag the caption
     * — it applies backpressure all the way to audio capture.
     *
     * Engines that leave this `false` are asked to translate finals only.
     * Partial captions still stream, carrying source text as their
     * translation until the sentence completes — the same degradation the
     * session already applies when a partial translation fails.
     */
    val translatesPartials: Boolean get() = true

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