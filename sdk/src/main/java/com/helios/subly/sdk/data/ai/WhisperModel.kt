package com.helios.subly.sdk.data.ai

/**
 * Whisper checkpoint catalog. Only multilingual variants are listed because
 * the PRD permanently locks the source language to auto-detect (English-only
 * `.en` variants don't support detection).
 *
 * Quantization is `q5_1` across the board (PRD A-note relaxes "4-bit" to
 * "<=5-bit"): smaller WER hit than `q4_0` at comparable size and speed on
 * modern ARMv8.2 cores.
 *
 * @property assetName Filename used both inside `assets/models/` (if shipped)
 *   and inside `filesDir/models/` (if downloaded). Stable name = trivial
 *   cache-hit logic in [WhisperModelLoader].
 * @property approxSizeBytes Used by the Consumer UI for "X MB download" copy
 *   and pre-flight free-space checks. Not authoritative.
 * @property bundledInAssets True only for the model we ship in-APK so first
 *   launch works offline.
 */
internal enum class WhisperModel(
    val assetName: String,
    val approxSizeBytes: Long,
    val bundledInAssets: Boolean,
) {
    TINY_Q5_1 (assetName = "ggml-tiny-q5_1.bin",  approxSizeBytes = 31L * 1024 * 1024,  bundledInAssets = true),
    BASE_Q5_1 (assetName = "ggml-base-q5_1.bin",  approxSizeBytes = 57L * 1024 * 1024,  bundledInAssets = false),
    SMALL_Q5_1(assetName = "ggml-small-q5_1.bin", approxSizeBytes = 190L * 1024 * 1024, bundledInAssets = false);

    companion object {
        /** Sensible default: the in-APK checkpoint. */
        val Default: WhisperModel = TINY_Q5_1
    }
}
