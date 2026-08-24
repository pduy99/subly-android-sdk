package com.helios.subly.asr.sherpa

/**
 * Sherpa-onnx streaming-model catalog.
 *
 * Resolves to a directory under `filesDir/sherpa-onnx/<dirName>/` that must
 * satisfy [assetPatterns]. The files are obtained, in priority order, by:
 *  1. already being present on disk (previously downloaded/extracted),
 *  2. unpacking a bundle the app shipped under `assets/sherpa-onnx/<dirName>/`,
 *  3. downloading [downloadFiles] from [downloadBaseUrl].
 *
 * @property dirName Stable folder name inside `filesDir/sherpa-onnx/`.
 * @property kind The role of this model in the pipeline.
 * @property assetPatterns Regex patterns for the required files. Deliberately
 *   variant-agnostic (e.g. `encoder.*\.onnx`) so either a downloaded int8
 *   build or an app-bundled fp32 build satisfies the check.
 * @property downloadFiles Exact file names to fetch when neither on-disk nor
 *   bundled. Empty means "no download source — app must provide".
 * @property downloadBaseUrl Base URL the [downloadFiles] are resolved against
 *   (`<base>/<file>`).
 * @property sampleRateHz Inference sample rate the model was trained on.
 * @property approxSizeBytes Used by the Consumer UI for download copy.
 */
internal enum class SherpaOnnxModel(
    val dirName: String,
    val kind: SherpaModelKind,
    val assetPatterns: List<Regex>,
    val downloadFiles: List<String> = emptyList(),
    val downloadBaseUrl: String = "",
    val sampleRateHz: Int = 16_000,
    val approxSizeBytes: Long = 0L,
) {
    /**
     * ASR: English streaming Zipformer transducer (chunk-16-left-128, int8).
     *
     * int8 variants are ~4x smaller than fp32 and noticeably faster on mobile
     * CPUs at a negligible WER cost — the right default for on-device.
     * Sourced from the maintainer's Hugging Face mirror of the official
     * `sherpa-onnx-streaming-zipformer-en-2023-06-26` release.
     */
    STREAMING_ZIPFORMER_EN(
        dirName = "sherpa-onnx-streaming-zipformer-en",
        kind = SherpaModelKind.ASR,
        assetPatterns = listOf(
            Regex("encoder.*\\.onnx"),
            Regex("decoder.*\\.onnx"),
            Regex("joiner.*\\.onnx"),
            Regex("tokens\\.txt"),
        ),
        downloadFiles = listOf(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt",
        ),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main",
        approxSizeBytes = 90L * 1024 * 1024,
    );

    companion object {
        /** Sensible default for first-launch wiring. */
        val Default: SherpaOnnxModel = STREAMING_ZIPFORMER_EN
    }
}
