package com.helios.subly.core.model

/**
 * High level lifecycle of the translation engine, surfaced to the Consumer app
 * (e.g. for status bar copy, overlay placeholders, analytics).
 */
sealed interface EngineState {
    /** Engine has not been started or has finished tearing down. */
    data object Idle : EngineState

    /** Engine is preparing engine models. */
    data class Preparing(
        val asrState: ModelPrepState,
        val translatorState: ModelPrepState,
    ) : EngineState

    data class Translating(
        val originalText: String,
        val translatedText: String,
        val mode: CaptureMode,
        val isFinal: Boolean,
    ) : EngineState

    /** Audio capture detected sustained silence; system likely DRM-blocked. */
    data object DrmBlocked : EngineState

    /** Engine is releasing resources. */
    data object Stopping : EngineState

    /** Terminal error state. */
    data class Error(val exception: Throwable) : EngineState

    enum class CaptureMode { AUDIO, VISION }
}