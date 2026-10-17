package com.helios.subly.translator.litertlm

import android.app.ActivityManager
import android.content.Context

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
enum class LiteRtLmModel(
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
        // Pinned to a revision, not `main`: approxSizeBytes is checked
        // against what lands on disk, so a re-upload on `main` would make
        // every completed download look partial. 19edb84 is the revision the
        // benchmark measured; `main` has since moved to metadata written for
        // litert-lm 0.18.0, newer than the runtime this module ships.
        downloadUrl =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/" +
                "19edb84c69a0212f29a6ef17ba0d6f278b6a1614/" +
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
    ),

    /**
     * Qwen3-4B-Instruct-2507, TorchAO mixed int4 (int4 linear weights, int8
     * embeddings, float KV cache), 2048-token context.
     *
     * Apache-2.0, ungated. The non-thinking instruct release, so it answers
     * without a reasoning preamble. Larger and slower to decode than the
     * 1.5B default, for markedly better translation; the KV cache alone can
     * reach 2 x 36 layers x 8 KV heads x 128 x 4 bytes x 2048 tokens =
     * 576 MiB.
     */
    QWEN3_4B_INSTRUCT_2507_INT4(
        fileName = "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        // Pinned for the same reason as the 1.5B entry.
        downloadUrl =
            "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/" +
                "4d409771b414d86b270ca54d27ffc45032a81142/" +
                "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        approxSizeBytes = 2_659_057_664L,
        // The export's full context; a caption never comes close.
        maxNumTokens = 2048,
        // Qwen3's model card claims 119 languages; listed are those the SDK's
        // recognisers can transcribe.
        supportedLanguages = listOf(
            "ar", "bn", "ca", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fr", "he", "hi",
            "hu", "id", "it", "ja", "ko", "ms", "nl", "no", "pl", "pt", "ro", "ru", "sv", "th",
            "tr", "uk", "vi", "zh",
        ),
    );

    companion object {
        /** The model that runs everywhere the SDK does. */
        val Default: LiteRtLmModel = QWEN2_5_1_5B_INSTRUCT_Q8

        /**
         * Below this much RAM the 4B model's ~1.5 GB working set plus the
         * recogniser's leaves too little for the rest of the phone. 8 GB
         * devices report roughly 7.3 to 7.6 GB total.
         */
        private const val LARGE_MODEL_MIN_RAM_BYTES = 7_000_000_000L

        /**
         * The best model this device can hold.
         *
         * Measured on a Galaxy S24 Ultra, GPU, same prompts
         * (`TranslatorModelProbeTest`): the 1.5B model translated "ringo o
         * taberu" as "I eat ramen" and produced Vietnamese mixed with Chinese
         * characters, while the 4B model got both right, at ~1.07 s to first
         * token instead of ~0.45 s. Translation that is wrong fast is not a
         * subtitle, so the 4B model is chosen wherever memory allows.
         */
        fun forDevice(context: Context): LiteRtLmModel {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            return if (memory.totalMem >= LARGE_MODEL_MIN_RAM_BYTES) QWEN3_4B_INSTRUCT_2507_INT4 else Default
        }
    }
}
