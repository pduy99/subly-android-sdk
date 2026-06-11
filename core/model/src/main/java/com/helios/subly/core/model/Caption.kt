package com.helios.subly.core.model

/**
 * A single caption produced by the pipeline.
 *
 * Emitted on [com.helios.subly.sdk.SublySession.captions] — *not* on the
 * lifecycle [EngineState] flow — so that:
 *  - lifecycle observers (status bar copy, loading UI) aren't spammed by
 *    per-utterance updates, and
 *  - caption collectors receive every final sentence without lifecycle
 *    conflation dropping them.
 */
data class Caption(
    /** The transcribed text in the source language. */
    val originalText: String,
    /** The translated text in the target language. */
    val translatedText: String,
    /**
     * `false` for in-progress partial hypotheses (may be revised),
     * `true` for completed sentences (stable; safe to persist).
     */
    val isFinal: Boolean,
    /** Which capture pipeline produced this caption. */
    val mode: CaptureMode,
) {
    enum class CaptureMode { AUDIO, VISION }
}