package com.helios.subly.sdk.data.ai

/**
 * Sherpa-onnx streaming-model catalog.
 *
 * Unlike whisper (single multilingual checkpoint), sherpa-onnx streaming
 * Zipformer / Conformer models are typically **per-language family**. Each
 * entry resolves to a directory under `filesDir/sherpa-onnx/<dirName>/`
 * that must contain (at minimum):
 *
 *  - `encoder.onnx`
 *  - `decoder.onnx`
 *  - `joiner.onnx`
 *  - `tokens.txt`
 *
 * The Consumer-side downloader is responsible for fetching/extracting
 * these artifacts. The SDK itself only resolves the path; if the directory
 * doesn't exist [SherpaOnnxTranscriber] degrades to a drain.
 *
 * @property dirName Stable folder name inside `filesDir/sherpa-onnx/`.
 * @property sampleRateHz Inference sample rate the model was trained on
 *   (almost always 16_000 for streaming Zipformer).
 * @property approxSizeBytes Used by the Consumer UI for download copy.
 */
internal enum class SherpaOnnxModel(
    val dirName: String,
    val sampleRateHz: Int,
    val approxSizeBytes: Long,
) {
    /**
     * Default int8-quantized streaming Zipformer (multilingual EN/CN).
     * Trained on `wenetspeech + librispeech + gigaspeech`. ~70 MB on disk.
     */
    STREAMING_ZIPFORMER_BILINGUAL_ZH_EN(
        dirName = "sherpa-onnx-streaming-zipformer-bilingual-zh-en",
        sampleRateHz = 16_000,
        approxSizeBytes = 70L * 1024 * 1024,
    );

    companion object {
        /** Sensible default for first-launch wiring. */
        val Default: SherpaOnnxModel = STREAMING_ZIPFORMER_BILINGUAL_ZH_EN
    }
}
