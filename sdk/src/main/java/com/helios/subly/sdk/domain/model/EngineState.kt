package com.helios.subly.sdk.domain.model

/**
 * High level lifecycle of the translation engine, surfaced to the Consumer app
 * (e.g. for status bar copy, overlay placeholders, analytics).
 */
sealed interface EngineState {
    /** Engine has not been started or has finished tearing down. */
    data object Idle : EngineState

    /** Engine is bootstrapping native resources / opening capture sources. */
    data object Starting : EngineState

    /** Engine is producing packets via the indicated capture pipeline. */
    data class Capturing(val mode: CaptureMode) : EngineState

    /** Audio capture detected sustained silence; system likely DRM-blocked. */
    data object DrmBlocked : EngineState

    /** Engine is releasing resources. */
    data object Stopping : EngineState

    /** Terminal error state. */
    data class Error(val message: String, val cause: Throwable? = null) : EngineState

    enum class CaptureMode { AUDIO, VISION }
}
