package com.helios.subly.core.model

/**
 * High-level lifecycle of a [com.helios.subly.sdk.SublySession], surfaced to
 * the consumer app (status copy, overlay placeholders, analytics).
 *
 * This is a *pure lifecycle* state machine — caption data is delivered
 * separately via [com.helios.subly.sdk.SublySession.captions].
 *
 * Valid transitions:
 * ```
 * Idle ──prepare()──▶ Preparing ──▶ Ready ◀──stop()── Running
 *                         │           │                  ▲
 *                         ▼           └────start()───────┘
 *                       Error ──prepare()──▶ Preparing (retry)
 *
 * (any state) ──close()──▶ Closed   [terminal]
 * ```
 */
sealed interface EngineState {

    /** Session created; preparation not yet started. */
    data object Idle : EngineState

    /**
     * Engine models are being downloaded / extracted / initialized.
     *
     * @param progress aggregated progress across both engines, normalized to
     * `0f..1f`. Render this directly in a progress bar; use [asrState] /
     * [translatorState] only if you need per-component detail.
     */
    data class Preparing(
        val asrState: ModelPrepState,
        val translatorState: ModelPrepState,
        val progress: Float,
    ) : EngineState

    /** Models are warm; [com.helios.subly.sdk.SublySession.start] may be called. */
    data object Ready : EngineState

    /** The capture → ASR → translation pipeline is running. */
    data object Running : EngineState

    /**
     * A failure occurred. Recoverable: calling
     * [com.helios.subly.sdk.SublySession.prepare] again retries preparation.
     */
    data class Error(val error: SublyError) : EngineState

    /** Terminal. The session has released its engines and cannot be reused. */
    data object Closed : EngineState
}