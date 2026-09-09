package com.helios.subly.translator.litertlm

/**
 * Catalog of `.litertlm` checkpoints the translator can run.
 *
 * These are big — this is the trade the module exists to make. ML Kit ships
 * ~30 MB per language pair and translates a caption in tens of milliseconds;
 * a general instruction-tuned LLM is three orders of magnitude larger and
 * seconds per caption, and buys context-aware output that a per-pair NMT
 * model cannot produce. Pick per product, not per benchmark.
 *
 * Only ungated, redistributable checkpoints are listed. Gemma builds are
 * deliberately absent: they are the stronger models, but the Gemma Terms of
 * Use are not an OSI licence and restrict downstream use, which is the wrong
 * default for an SDK that third parties redistribute.
 *
 * @property fileName Stable name under `filesDir/litertlm/`.
 * @property downloadUrl Direct URL. Repoint at your own mirror for production
 *   — this enum is the single source of truth.
 * @property approxSizeBytes Real content-length; also drives the free-space
 *   check before the download starts.
 * @property maxNumTokens litertlm kv-cache size (input + output tokens).
 * @property supportedLanguages BCP-47 tags the model card claims. A claim,
 *   not a measurement — the SDK cannot verify an LLM's language coverage.
 */
internal enum class LiteRtLmModel(
    val fileName: String,
    val downloadUrl: String,
    val approxSizeBytes: Long,
    val maxNumTokens: Int,
    val supportedLanguages: List<String>,
) {
    /**
     * Qwen2.5 1.5B Instruct, int8, exported with a 4096-token kv cache.
     *
     * Apache-2.0 and ungated on Hugging Face, which is what makes it usable
     * here. The f32 export of the same model is 6.2 GB and does not fit the
     * memory budget of the devices this SDK targets.
     */
    QWEN2_5_1_5B_INSTRUCT_Q8(
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        downloadUrl =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        approxSizeBytes = 1_597_931_520L,
        // Well under the 4096 the checkpoint was exported with, and far above
        // what a caption needs: the prompt is ~40 tokens and the output a
        // sentence. A smaller cache is a smaller resident footprint, which
        // matters much more than context length for this workload.
        maxNumTokens = 2048,
        // Qwen2.5's model card claims 29+ languages; listed here are the ones
        // that overlap with what the SDK's ASR engines can transcribe or that
        // the benchmark targets.
        supportedLanguages = listOf(
            "ar", "de", "en", "es", "fr", "it", "ja", "ko", "nl", "pl",
            "pt", "ru", "th", "tr", "vi", "zh",
        ),
    );

    companion object {
        /** Sensible default for first-launch wiring. */
        val Default: LiteRtLmModel = QWEN2_5_1_5B_INSTRUCT_Q8
    }
}
