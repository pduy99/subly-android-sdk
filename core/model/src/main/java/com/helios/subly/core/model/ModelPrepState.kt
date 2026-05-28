package com.helios.subly.core.model

/**
 * Represents the preparation state of ML models required by the engine.
 *
 * Collect a `StateFlow` of this type from `SublyEngine.prepareModels` to observe
 * download and initialization progress and update the consumer UI accordingly.
 */
sealed interface ModelPrepState {

    /** No preparation has been requested yet, or the engine has been reset. */
    data object Idle : ModelPrepState

    /**
     * A model is actively being prepared (extracted from assets or downloaded).
     *
     * @param progress Overall preparation progress in the range [0.0, 1.0].
     * @param description Human-readable label describing the current step.
     */
    data class Preparing(val progress: Float, val description: String) : ModelPrepState

    /** All required models are ready; the pipeline can be started. */
    data object Ready : ModelPrepState

    /**
     * Preparation failed.
     *
     * @param message Short description of the failure.
     * @param cause   Underlying exception, if available.
     */
    data class Error(val message: String, val cause: Throwable? = null) : ModelPrepState
}
