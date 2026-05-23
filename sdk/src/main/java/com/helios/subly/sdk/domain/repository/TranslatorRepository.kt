package com.helios.subly.sdk.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Boundary over the on-device NMT bridge (Phase 3b).
 *
 * Whisper's built-in `translate=true` flag is English-only, so the engine
 * relies on this repository to reach the v1 target matrix `{en, vi, es}`
 * (and any future addition) by chaining
 * `Whisper (auto -> source text) -> Translator (source -> target)`.
 *
 * Implementations are expected to:
 *  - Cache active per-pair translator instances (ML Kit `Translator`s are
 *    cheap to construct but hold native model memory; an LRU keeps a handful
 *    warm without ballooning RSS).
 *  - Pull the (source, target) on-device model on-demand, surfacing
 *    failure via [DownloadResult] so the engine can degrade gracefully.
 *  - Pass-through when `source == target` (no NMT round-trip needed).
 */
interface TranslatorRepository {

    /**
     * Translate [text] from [sourceBcp47] to [targetBcp47].
     *
     * The flow emits at most one translated string per call, then completes.
     * Modelled as [Flow] (vs. `suspend`) to match the streaming shape of the
     * audio pipeline so use cases can flat-map without bridging suspensions.
     *
     * Implementations MUST pass the input through unchanged when
     * `sourceBcp47.equals(targetBcp47, ignoreCase = true)`.
     */
    fun translate(text: String, sourceBcp47: String, targetBcp47: String): Flow<String>

    /**
     * Ensure the on-device translation pair (`source -> target`) is present.
     * Safe to call repeatedly; idempotent after a successful download.
     */
    suspend fun ensureModel(sourceBcp47: String, targetBcp47: String): DownloadResult

    /** Identify the BCP-47 source language of [text], or `null` if undetermined. */
    suspend fun identifySource(text: String): String?

    /** Release cached translator instances and their native resources. */
    fun release()
}

/** Outcome of a per-pair model download request. */
sealed class DownloadResult {
    object Success : DownloadResult()
    /** Network or storage failure. The cause is preserved for logs/UX. */
    data class Failed(val cause: Throwable) : DownloadResult()
    /** No model exists for this language pair (e.g. unsupported BCP-47). */
    object Unsupported : DownloadResult()
}
