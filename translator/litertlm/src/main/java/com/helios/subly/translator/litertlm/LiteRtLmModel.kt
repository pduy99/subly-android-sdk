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
 * Only ungated, redistributable (Apache-2.0) checkpoints are listed. Gemma 3
 * and earlier are absent because the Gemma Terms of Use restrict downstream
 * use; Gemma 4 is Apache-2.0.
 *
 * @property fileName Stable name under `filesDir/litertlm/`.
 * @property downloadUrl Direct URL. Repoint at your own mirror for production
 *   — this enum is the single source of truth.
 * @property approxSizeBytes Real content-length; also drives the free-space
 *   check before the download starts.
 * @property maxNumTokens litertlm kv-cache size (input + output tokens).
 * @property supportedLanguages BCP-47 tags the model card claims. A claim,
 *   not a measurement — the SDK cannot verify an LLM's language coverage.
 * @property promptStyle How the model expects to be asked to translate.
 */
enum class LiteRtLmModel(
    val fileName: String,
    val downloadUrl: String,
    val approxSizeBytes: Long,
    val maxNumTokens: Int,
    val supportedLanguages: List<String>,
    val promptStyle: PromptStyle = PromptStyle.SYSTEM_INSTRUCTION,
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
     * reach 2 x 36 layers x 8 KV heads x 128 x 4 bytes x 1024 tokens =
     * 288 MiB.
     */
    QWEN3_4B_INSTRUCT_2507_INT4(
        fileName = "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        // Pinned for the same reason as the 1.5B entry.
        downloadUrl =
            "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/" +
                "4d409771b414d86b270ca54d27ffc45032a81142/" +
                "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        approxSizeBytes = 2_659_057_664L,
        // Half the export's 2048-token context. A sentence's conversation
        // (system prompt ~60 tokens, a few pieces, their translations) stays
        // well under 1024, and the fp32 KV cache drops from 576 to 288 MiB:
        // the 4B pipeline was being killed for memory on a 12 GB phone.
        maxNumTokens = 1024,
        // Qwen3's model card claims 119 languages; listed are those the SDK's
        // recognisers can transcribe.
        supportedLanguages = listOf(
            "ar", "bn", "ca", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fr", "he", "hi",
            "hu", "id", "it", "ja", "ko", "ms", "nl", "no", "pl", "pt", "ro", "ru", "sv", "th",
            "tr", "uk", "vi", "zh",
        ),
    ),

    /**
     * Gemma 4 E2B instruct (Apache-2.0), Google's mixed 2/4/8-bit mobile
     * quantisation. About 2B effective parameters per token, with
     * per-layer embeddings memory-mapped rather than resident.
     *
     * Google's card: 52 tok/s decode, 0.3 s to first token and 676 MB on a
     * Galaxy S26 Ultra GPU.
     */
    GEMMA4_E2B_IT(
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        approxSizeBytes = 2_588_147_712L,
        maxNumTokens = 1024,
        supportedLanguages = GEMMA4_LANGUAGES,
    ),

    /** Gemma 4 E4B instruct (Apache-2.0): about twice E2B's compute per token. */
    GEMMA4_E4B_IT(
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
                "2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
        approxSizeBytes = 3_659_530_240L,
        maxNumTokens = 1024,
        supportedLanguages = GEMMA4_LANGUAGES,
    ),

    /**
     * Hy-MT2-1.8B (Tencent, Apache-2.0), int8: a translation model, not a
     * chat model. 33 languages; on FLORES-200 its authors report about 90%
     * of Gemini 3.1 Pro's XCOMET score across all directions.
     */
    HY_MT2_1_8B(
        fileName = "Hy-MT2-1.8B_int8.litertlm",
        downloadUrl =
            "https://huggingface.co/litert-community/Hy-MT2-1.8B/resolve/" +
                "0d2c5681276f71b61c4f2a14fbb9791341b28017/Hy-MT2-1.8B_int8.litertlm",
        approxSizeBytes = 1_827_026_224L,
        maxNumTokens = 1024,
        supportedLanguages = listOf(
            "ar", "bn", "cs", "de", "en", "es", "fa", "fr", "gu", "he", "hi", "id", "it", "ja",
            "kk", "km", "ko", "mn", "mr", "ms", "my", "nl", "pl", "pt", "ru", "ta", "te", "th",
            "tl", "tr", "uk", "ur", "vi", "zh",
        ),
        promptStyle = PromptStyle.HY_MT,
    );

    /** How a model is asked to translate. */
    enum class PromptStyle {
        /** A chat model: the task in the system instruction, the text as the user message. */
        SYSTEM_INSTRUCTION,

        /**
         * Hunyuan-MT: no system prompt; the instruction and the text in one
         * user message, in Chinese when either side is Chinese. One message
         * per conversation: its chat template cannot extend a conversation.
         */
        HY_MT,
    }

    companion object {
        /** The model that runs everywhere the SDK does. */
        val Default: LiteRtLmModel = QWEN2_5_1_5B_INSTRUCT_Q8

        /**
         * The 4B model only on 12 GB-class phones (a 12 GB Galaxy S24 Ultra
         * reports 11.35 GB). On that phone the app reached 5.4-6.3 GB RSS
         * preparing the 4B model on the GPU next to whisper, and was killed
         * for memory; an 8 GB phone has no room for it at all.
         */
        private const val LARGE_MODEL_MIN_RAM_BYTES = 10_500_000_000L

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
        fun forDevice(context: Context): LiteRtLmModel =
            if (canRunLargeModel(context)) GEMMA4_E2B_IT else Default

        /**
         * Whether this device can run [QWEN3_4B_INSTRUCT_2507_INT4]. Below
         * that, no catalog model is a good live translator for every pair:
         * on a Galaxy S10 (7.6 GB) the 4B model took ~10 s to its first token,
         * Qwen2.5-1.5B answered Chinese with Chinese, and Qwen3-1.7B (tried,
         * not shipped) drifted into English, Thai and leaked reasoning. Apps
         * should use a per-pair NMT translator on such devices.
         */
        fun canRunLargeModel(context: Context): Boolean {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            return memory.totalMem >= LARGE_MODEL_MIN_RAM_BYTES
        }
    }
}

/**
 * Languages Gemma 4 handles well that the SDK can transcribe. Top level, not
 * in the companion: enum entries are built before the companion exists.
 */
private val GEMMA4_LANGUAGES = listOf(
    "ar", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt", "ru",
    "th", "tr", "uk", "vi", "zh",
)
