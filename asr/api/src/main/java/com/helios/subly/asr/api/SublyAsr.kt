package com.helios.subly.asr.api

import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.flow.Flow

/**
 * A speech-to-text engine.
 *
 * Implementations are *session-scoped*: each [com.helios.subly.sdk.SublySession]
 * owns exactly one instance and is responsible for calling [release].
 * Implementations therefore don't need to support concurrent sessions, but
 * MUST support the prepare → transcribe → release → (new instance) lifecycle.
 */
interface SublyAsr {

    /**
     * Whether each [AsrResult.Final] is already a complete utterance/clause.
     *
     * Streaming, endpoint-based engines (e.g. sherpa-onnx) finalize on a
     * trailing pause, so their finals can be emitted one-to-one. Engines that
     * emit arbitrary chunks leave this `false` and have their finals pooled
     * into sentences downstream.
     */
    val emitsCompleteUtterances: Boolean get() = false

    /**
     * Transcribes a stream of raw audio frames into ASR results.
     *
     * ### Contract
     * - MUST only be collected after [prepareModel] has reached
     *   [ModelPrepState.Ready]. If collected earlier, the returned flow MUST
     *   fail on collection with [IllegalStateException] (fail loudly — never
     *   silently drain audio, which presents to the user as "ready but no
     *   captions").
     * - May emit zero or more [AsrResult.Partial] (revisable hypotheses)
     *   interleaved with [AsrResult.Final] (stable utterances). Engines that
     *   don't support partials emit finals only.
     * - Runtime failures propagate as flow exceptions (handled upstream by
     *   the session).
     */
    fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult>

    /**
     * Prepares (downloads/extracts/initializes) the model for [config].
     *
     * Cold flow. See the contract on [ModelPrepState]: must terminate with
     * [ModelPrepState.Ready] or [ModelPrepState.Error], must not throw, must
     * report progress normalized to `0f..1f`, and must be re-collectable for
     * retry / fast-path-when-already-prepared.
     */
    fun prepareModel(config: LanguageConfig): Flow<ModelPrepState>

    /**
     * Releases native resources. Idempotent. After release, the instance is
     * dead — create a new one rather than re-preparing.
     */
    fun release()

    /**
     * Returns the list of supported BCP-47 language tags for speech-to-text.
     */
    fun supportedLanguages(): List<String>
}