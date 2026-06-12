package com.helios.subly.core.model

/**
 * Per-engine model preparation state.
 *
 * ## Contract (binding on every [com.helios.subly.asr.api.SublyAsr] and
 * [com.helios.subly.translator.api.SublyTranslator] implementation)
 *
 * 1. A `prepareModel` flow MUST terminate by emitting [Ready] or [Error].
 *    Completing without a terminal emission is a contract violation —
 *    `SublySession` defensively maps it to an error, never to `Ready`.
 * 2. [Preparing.progress] MUST be normalized to `0f..1f`.
 * 3. The flow MUST NOT throw; failures are emitted as [Error].
 * 4. The flow MUST be safely re-collectable: collecting again after [Error]
 *    retries preparation; collecting when already prepared emits [Ready]
 *    immediately.
 */
sealed interface ModelPrepState {

    /** Determining whether the model is already available locally. */
    data object Checking : ModelPrepState

    /**
     * Downloading / extracting / initializing.
     *
     * @param progress normalized `0f..1f`. Engines that cannot report
     * granular progress (e.g. ML Kit downloads) should hold a constant value
     * and document the phase as indeterminate.
     */
    data class Preparing(val progress: Float) : ModelPrepState

    /** Terminal: the model is loaded and ready for inference. */
    data object Ready : ModelPrepState

    /** Terminal: preparation failed. Re-collecting the flow retries. */
    data class Error(val cause: Throwable) : ModelPrepState
}