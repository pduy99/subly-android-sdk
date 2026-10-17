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
    val languages: Set<String> = emptySet(),
    val numThreads: Int = 1,
    /**
     * Expected SHA-256 of downloaded files, by name. A file that does not
     * match is deleted, so a corrupted or substituted download never loads.
     * Required for files fetched from a mirror rather than the publisher.
     */
    val sha256: Map<String, String> = emptyMap(),
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
        languages = setOf("en"),
    ),

    /**
     * ASR: Mandarin streaming Zipformer transducer (large, int8), trained on
     * the multi-zh-hans corpus collection.
     *
     * Chosen by measurement (`ChineseAsrProbeTest`, Galaxy S10): 14.5%
     * character error on fast real-world dialogue where Vosk's small Chinese
     * model had 53.9% and whisper small 42.4%, and 5.7-6.1% on FLEURS read
     * speech, at 0.31x real time with four threads. Pinned to a revision so
     * the files the size checks expect cannot change underneath.
     */
    STREAMING_ZIPFORMER_ZH(
        dirName = "sherpa-onnx-streaming-zipformer-zh-2025-06-30",
        kind = SherpaModelKind.ASR,
        assetPatterns = listOf(
            Regex("encoder.*\\.onnx"),
            Regex("decoder.*\\.onnx"),
            Regex("joiner.*\\.onnx"),
            Regex("tokens\\.txt"),
        ),
        downloadFiles = listOf(
            "encoder.int8.onnx",
            "decoder.onnx",
            "joiner.int8.onnx",
            "tokens.txt",
        ),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/resolve/" +
                "ad658fa0201659a09ea3c176129a191c77ecae8f",
        approxSizeBytes = 167_360_920L,
        languages = setOf("zh"),
        // A large model: one thread cannot keep up with speech on a 2019
        // phone. Four measured 0.31x real time on an Exynos 9820.
        numThreads = 4,
    ),

    /**
     * Offline ASR: SenseVoice small (int8, 2025-09-09), Mandarin plus
     * English, Japanese, Korean and Cantonese, with inverse text
     * normalisation (punctuation, digits).
     *
     * The most accurate Mandarin model measured (`ChineseAsrProbeTest`,
     * Galaxy S10): 12.7% character error on fast real-world dialogue and
     * 6.1-9.1% on FLEURS, at 0.13x real time. Offline, so it runs on
     * VAD-cut segments ([SenseVoiceTranscriber]).
     */
    SENSE_VOICE(
        dirName = "sherpa-onnx-sense-voice-2025-09-09",
        kind = SherpaModelKind.OFFLINE_ASR,
        assetPatterns = listOf(Regex("model.*\\.onnx"), Regex("tokens\\.txt")),
        downloadFiles = listOf("model.int8.onnx", "tokens.txt"),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/" +
                "355f4d4884d8afd08aef04b9007a8556d7b463b2",
        approxSizeBytes = 237_400_000L,
        languages = setOf("zh", "en", "ja", "ko", "yue"),
        numThreads = 4,
    ),

    /**
     * Punctuation for Chinese (and English) text: CT-Transformer, int8.
     *
     * Neither Mandarin recogniser punctuates, so a final arrives as one
     * unbroken run ("硬通货随便吃都有真便宜啊呃你是这个卖家对是我走吧"), which
     * neither sentence assembly nor a translator can cut into subtitle lines.
     * This restores 。，？ (~20 ms per final on a desktop CPU).
     *
     * The publisher (k2-fsa) ships the int8 build only inside a tarball; this
     * is a third-party Hugging Face copy of the extracted file, pinned to a
     * revision and verified against the SHA-256 of the file in the official
     * `punctuation-models` release.
     */
    PUNCT_ZH_EN(
        dirName = "sherpa-onnx-punct-ct-transformer-zh-en-int8",
        kind = SherpaModelKind.PUNCTUATION,
        assetPatterns = listOf(Regex("model.*\\.onnx")),
        downloadFiles = listOf("model.int8.onnx"),
        downloadBaseUrl =
            "https://huggingface.co/lorneluo/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/" +
                "resolve/fc2be466e3c11927b306a31cdee23c9c38da44cc",
        approxSizeBytes = 75_519_198L,
        languages = setOf("zh"),
        sha256 = mapOf("model.int8.onnx" to "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1"),
    ),

    /** Silero VAD (~0.6 MB), cuts speech segments for [SENSE_VOICE]. */
    SILERO_VAD(
        dirName = "silero-vad",
        kind = SherpaModelKind.VAD,
        assetPatterns = listOf(Regex("silero_vad\\.onnx")),
        downloadFiles = listOf("silero_vad.onnx"),
        downloadBaseUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models",
        approxSizeBytes = 643_854L,
    );

    companion object {
        /** Sensible default for first-launch wiring. */
        val Default: SherpaOnnxModel = STREAMING_ZIPFORMER_EN

        /** The model for a BCP-47 source tag, or null if none covers it. */
        fun forLanguage(tag: String): SherpaOnnxModel? {
            val primary = tag.substringBefore('-').lowercase()
            return entries.firstOrNull { it.kind == SherpaModelKind.ASR && primary in it.languages }
        }

        /** Every source language some streaming model covers. */
        val supportedLanguages: List<String>
            get() = entries.filter { it.kind == SherpaModelKind.ASR }.flatMap { it.languages }.distinct()
    }
}
