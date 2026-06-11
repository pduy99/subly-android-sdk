package com.helios.subly.core.model

/**
 * Typed error hierarchy surfaced through [EngineState.Error].
 *
 * Consumers can branch on the subtype (e.g. show a "check your connection"
 * message for a failed model download vs. a generic failure screen) instead
 * of string-matching raw [Throwable]s. The original cause is always preserved
 * for logging/analytics.
 */
sealed class SublyError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Which engine a preparation failure originated from. */
    enum class Component { ASR, TRANSLATOR }

    /**
     * Model preparation (download, extraction, or native init) failed.
     *
     * @param component the failing engine, or `null` if it could not be
     * determined (e.g. an engine violated the prepare contract by completing
     * without a terminal state).
     */
    class ModelPreparationFailed(
        val component: Component?,
        cause: Throwable? = null,
    ) : SublyError(
        message = "Model preparation failed for ${component?.name ?: "unknown component"}.",
        cause = cause,
    )

    /** A translation call failed while the pipeline was running. */
    class TranslationFailed(cause: Throwable) :
        SublyError("Translation failed.", cause)

    /** The capture → ASR → translation pipeline failed for any other reason. */
    class PipelineFailed(cause: Throwable) :
        SublyError("Translation pipeline failed.", cause)
}