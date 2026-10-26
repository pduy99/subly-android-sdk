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
    /** How to load an [SherpaModelKind.OFFLINE_ASR] model; null for other kinds. */
    val offlineFamily: OfflineFamily? = null,
    /**
     * Pause, in seconds, that ends an offline model's speech segment. Each
     * segment becomes one caption, so the default is the pause that ends a
     * streaming caption ([CaptionEndpointTuning.SPEECH_PAUSE]): shorter cuts
     * a sentence at every breath, and each piece is translated on its own.
     */
    val vadMinSilenceSec: Float = 0.8f,
    /**
     * Speech, in seconds, after which the VAD looks for any short gap to end
     * a segment (a soft cap: segments can run past it). The default matches
     * [CaptionEndpointTuning.RUN_ON_CAP].
     */
    val maxSegmentSec: Float = 12f,
    /**
     * Whether the model writes its own punctuation. For one that does, a
     * final without a terminator is a sentence cut by a pause, not one that
     * ended: "…one of the guys, or" / "gals."
     */
    val punctuates: Boolean = false,
) {
    /**
     * ASR: English streaming Zipformer transducer from Kroko (2025-08-06),
     * cased and punctuated.
     *
     * Chosen by measurement (`AsrProbeTest`, Galaxy S10, FLEURS): 7.9% word
     * error at 0.06x real time, against 13.7% for the previous
     * zipformer-en-2023-06-26 (upper-case, unpunctuated) and 20.7% for Vosk.
     * Parakeet TDT 0.6B was more accurate (5.4%) but is a 661 MB offline
     * model; this one streams and is 71 MB.
     */
    STREAMING_KROKO_EN(
        dirName = "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
        kind = SherpaModelKind.ASR,
        assetPatterns = TRANSDUCER_FILES,
        downloadFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/" +
                "572aaf4e2e0c603c3fc2a574d096e755a178faa1",
        approxSizeBytes = 71_053_214L,
        languages = setOf("en"),
        numThreads = 2,
        punctuates = true,
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
     * ASR: Spanish streaming Zipformer transducer from Kroko (2025-08-06),
     * cased and punctuated.
     *
     * Measured (`AsrProbeTest`, Galaxy S10, FLEURS): 7.0% word error at 0.13x
     * real time. Moonshine-base-es (4.7%) and Parakeet TDT v3 (7.6%) are
     * offline; Moonshine ran at 0.5x real time, too close to the limit for
     * re-decoded live partials. Vosk measured 13.9%.
     */
    STREAMING_KROKO_ES(
        dirName = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
        kind = SherpaModelKind.ASR,
        assetPatterns = TRANSDUCER_FILES,
        downloadFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06/resolve/" +
                "20cf7a4921613397841d31168796cade5b866585",
        approxSizeBytes = 155_838_792L,
        languages = setOf("es"),
        numThreads = 2,
        punctuates = true,
    ),

    /**
     * Offline ASR: ReazonSpeech k2 v2, a Japanese Zipformer transducer
     * (int8) trained on the 35,000-hour ReazonSpeech corpus of Japanese TV.
     *
     * Measured (`AsrProbeTest`, Galaxy S10, FLEURS): 21.2% character error
     * at 0.08x real time, against 29.5% for Vosk and 76% for SenseVoice.
     * Qwen3-ASR 0.6B was more accurate (17.2%) but ran at 0.6x real time
     * and is a 987 MB download. Runs on VAD-cut segments
     * ([OfflineVadTranscriber]); output is unpunctuated.
     */
    REAZONSPEECH_JA(
        dirName = "sherpa-onnx-reazonspeech-k2-v2",
        kind = SherpaModelKind.OFFLINE_ASR,
        assetPatterns = TRANSDUCER_FILES,
        downloadFiles = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj/reazonspeech-k2-v2/resolve/7ebed8f70b8ef67f656aa08e3084e5885202d0e6",
        approxSizeBytes = 160_372_200L,
        languages = setOf("ja"),
        numThreads = 4,
        offlineFamily = OfflineFamily.TRANSDUCER,
        // Measured in the app: with the default pause a 9.4 s segment
        // decoded as one word ("これ"); the model is reliable on the short
        // segments it was trained on, and Japanese readers pause at the end
        // of a sentence anyway. Cap measured on five FLEURS clips (Galaxy
        // S24 Ultra): 17.3% CER at 8 s, 14.7% at 6 s, 16.5% at 5 s.
        vadMinSilenceSec = 0.3f,
        maxSegmentSec = 6f,
    ),

    /**
     * Offline ASR: Vietnamese Zipformer transducer, 30M parameters (int8,
     * 2026-02-09).
     *
     * Measured (`AsrProbeTest`, Galaxy S10, FLEURS): 12.7% word error at
     * 0.06x real time, against 47.1% for Vosk, 23.2% for Moonshine-base-vi
     * and 12.2% for the 77 MB zipformer-vi-2025-04-20. Upper-case and
     * unpunctuated; runs on VAD-cut segments ([OfflineVadTranscriber]).
     */
    ZIPFORMER_VI(
        dirName = "sherpa-onnx-zipformer-vi-30M-2026-02-09",
        kind = SherpaModelKind.OFFLINE_ASR,
        assetPatterns = TRANSDUCER_FILES,
        downloadFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
        downloadBaseUrl =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09/resolve/" +
                "83e140db6d23fbb8480fd5fb868f74ab80e7092c",
        approxSizeBytes = 33_920_802L,
        languages = setOf("vi"),
        numThreads = 4,
        offlineFamily = OfflineFamily.TRANSDUCER,
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
        offlineFamily = OfflineFamily.SENSE_VOICE,
        // Punctuated afterwards (Chinese), so short segments are fine.
        vadMinSilenceSec = 0.3f,
        maxSegmentSec = 8f,
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
        val Default: SherpaOnnxModel = STREAMING_KROKO_EN

        /**
         * The recogniser chosen for each supported source language, by
         * measurement (see each entry). One model per language, streaming
         * where a streaming model was within reach of the best offline one.
         */
        private val BEST_BY_LANGUAGE: Map<String, SherpaOnnxModel> = mapOf(
            "en" to STREAMING_KROKO_EN,
            "zh" to STREAMING_ZIPFORMER_ZH,
            "ja" to REAZONSPEECH_JA,
            "es" to STREAMING_KROKO_ES,
            "vi" to ZIPFORMER_VI,
        )

        /** The best recogniser for a BCP-47 source tag, or null if none covers it. */
        fun bestFor(tag: String): SherpaOnnxModel? = BEST_BY_LANGUAGE[primary(tag)]

        /** The streaming model for a BCP-47 source tag, or null if none covers it. */
        fun forLanguage(tag: String): SherpaOnnxModel? {
            val primary = primary(tag)
            return entries.firstOrNull { it.kind == SherpaModelKind.ASR && primary in it.languages }
        }

        /** Every source language some streaming model covers. */
        val supportedLanguages: List<String>
            get() = entries.filter { it.kind == SherpaModelKind.ASR }.flatMap { it.languages }.distinct()

        /** Every source language [bestFor] serves. */
        val bestLanguages: List<String> get() = BEST_BY_LANGUAGE.keys.toList()

        private fun primary(tag: String) = tag.substringBefore('-').lowercase()
    }
}

/** Transducer files, matched loosely so int8 and fp32 builds both satisfy them. */
private val TRANSDUCER_FILES = listOf(
    Regex("encoder.*\\.onnx"),
    Regex("decoder.*\\.onnx"),
    Regex("joiner.*\\.onnx"),
    Regex("tokens\\.txt"),
)

/** How an offline model is configured in sherpa-onnx. */
internal enum class OfflineFamily { SENSE_VOICE, TRANSDUCER }
