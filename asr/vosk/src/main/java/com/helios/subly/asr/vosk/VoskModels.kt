package com.helios.subly.asr.vosk

/**
 * Catalog of downloadable Vosk (Kaldi) acoustic models, keyed by the BCP-47
 * language tags the SDK exposes.
 *
 * Only the **small** (`vosk-model-small-*`) models are listed: they are the
 * ~30-50 MB streaming-friendly builds intended for mobile, which is the only
 * tier that makes sense on-device. The larger Vosk models are hundreds of MB
 * to ~1.8 GB and are server-grade.
 *
 * Each entry resolves to a directory under `filesDir/vosk/<dirName>/` that,
 * after the zip is unpacked and its top-level folder flattened, contains the
 * standard Kaldi layout (`am/`, `conf/`, `graph/`, `ivector/`). Files are
 * obtained, in priority order, by:
 *  1. already being present on disk (previously downloaded/unpacked) -> reuse,
 *  2. downloading [zipUrl] and unpacking it.
 *
 * Model names track the official Vosk catalog at
 * https://alphacephei.com/vosk/models — this enum is the single place to bump a
 * version or repoint [DOWNLOAD_BASE_URL] at your own mirror for production.
 *
 * @property matchTags BCP-47 tags (lowercased) that select this model. Matching
 *   is tried on the full tag first, then on the primary language subtag, so
 *   both `en` and `en-US` resolve to [EN].
 * @property dirName Stable folder name under `filesDir/vosk/` AND the name of
 *   the top-level directory inside the downloaded zip (Vosk zips wrap the model
 *   in a folder named exactly after the model).
 * @property approxSizeBytes Used for download/extract progress and Consumer UI
 *   copy. Approximate — the real content-length drives progress when available.
 */
private const val MB = 1024L * 1024L

internal enum class VoskModels(
    val matchTags: Set<String>,
    val dirName: String,
    val approxSizeBytes: Long,
) {
    EN(setOf("en", "en-us"), "vosk-model-small-en-us-0.15", 41L * MB),
    EN_IN(setOf("en-in"), "vosk-model-small-en-in-0.4", 36L * MB),
    ZH(setOf("zh", "zh-cn", "cn"), "vosk-model-small-cn-0.22", 42L * MB),
    RU(setOf("ru"), "vosk-model-small-ru-0.22", 45L * MB),
    FR(setOf("fr"), "vosk-model-small-fr-0.22", 41L * MB),
    DE(setOf("de"), "vosk-model-small-de-0.15", 45L * MB),
    ES(setOf("es"), "vosk-model-small-es-0.42", 39L * MB),
    PT(setOf("pt"), "vosk-model-small-pt-0.3", 31L * MB),
    TR(setOf("tr"), "vosk-model-small-tr-0.3", 35L * MB),
    VI(setOf("vi", "vn"), "vosk-model-small-vn-0.4", 32L * MB),
    IT(setOf("it"), "vosk-model-small-it-0.22", 48L * MB),
    NL(setOf("nl"), "vosk-model-small-nl-0.22", 39L * MB),
    CA(setOf("ca"), "vosk-model-small-ca-0.4", 42L * MB),
    JA(setOf("ja"), "vosk-model-small-ja-0.22", 48L * MB),
    KO(setOf("ko"), "vosk-model-small-ko-0.22", 82L * MB),
    HI(setOf("hi"), "vosk-model-small-hi-0.22", 42L * MB),
    PL(setOf("pl"), "vosk-model-small-pl-0.22", 50L * MB),
    UK(setOf("uk"), "vosk-model-small-uk-v3-small", 133L * MB);

    /** Fully-qualified download URL for this model's zip. */
    val zipUrl: String get() = "$DOWNLOAD_BASE_URL/$dirName.zip"

    companion object {
        /** Base URL the per-model zips are resolved against (`<base>/<name>.zip`). */
        const val DOWNLOAD_BASE_URL = "https://alphacephei.com/vosk/models"

        /** Default for first-launch wiring. */
        val Default: VoskModels = EN

        /**
         * Resolves a BCP-47 [languageTag] (e.g. `en`, `en-US`, `zh-CN`) to a
         * model, or `null` if unsupported. Tries the full tag, then the primary
         * subtag — case-insensitively.
         */
        fun forLanguage(languageTag: String): VoskModels? {
            val tag = languageTag.trim().lowercase()
            if (tag.isEmpty()) return null
            val primary = tag.substringBefore('-')
            return VoskModels.entries.firstOrNull { it.matchTags.contains(tag) }
                ?: VoskModels.entries.firstOrNull { it.matchTags.contains(primary) }
        }

        /** BCP-47 primary subtags the engine can transcribe. */
        fun supportedLanguageTags(): List<String> =
            VoskModels.entries.map { it.matchTags.first().substringBefore('-') }.distinct()
    }
}
