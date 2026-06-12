package com.helios.subly.asr.sherpa

/**
 * Sherpa-onnx streaming-model catalog.
 *
 * Resolves to a directory under `filesDir/sherpa-onnx/<dirName>/`
 * that must match the required `assetPatterns`.
 *
 * @property dirName Stable folder name inside `filesDir/sherpa-onnx/`.
 * @property kind The role of this model in the pipeline.
 * @property assetPatterns Glob/Regex patterns for required files (e.g. `encoder.*\.ort`).
 * @property sampleRateHz Inference sample rate the model was trained on.
 * @property approxSizeBytes Used by the Consumer UI for download copy.
 */
internal enum class SherpaOnnxModel(
    val dirName: String,
    val kind: SherpaModelKind,
    val assetPatterns: List<Regex>,
    val sampleRateHz: Int = 16_000,
    val approxSizeBytes: Long = 0L,
) {
    /** ASR: English streaming Zipformer. */
    STREAMING_ZIPFORMER_EN(
        dirName = "sherpa-onnx-streaming-zipformer-en",
        kind = SherpaModelKind.ASR,
        assetPatterns = listOf(
            Regex("encoder-epoch-99-avg-1.onnx"),
            Regex("decoder-epoch-99-avg-1.onnx"),
            Regex("joiner-epoch-99-avg-1.onnx"),
            Regex("tokens.txt")
        ),
        approxSizeBytes = 300 * 1024 * 1024,
    ),

    /** Punctuation: English online punctuation model. */
    PUNCTUATION_EN(
        dirName = "sherpa-onnx-online-punct-en",
        kind = SherpaModelKind.PUNCTUATION,
        assetPatterns = listOf(
            Regex("model.*\\.onnx"),
            Regex("bpe\\.vocab")
        ),
        approxSizeBytes = 30L * 1024 * 1024,
    );

    companion object {
        /** Sensible default for first-launch wiring. */
        val Default: SherpaOnnxModel = STREAMING_ZIPFORMER_EN
    }
}
