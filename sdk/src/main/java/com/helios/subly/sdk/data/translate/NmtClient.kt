package com.helios.subly.sdk.data.translate

/**
 * Test seam over a single ML Kit [com.google.mlkit.nl.translate.Translator]
 * instance. One client = one (source, target) pair.
 *
 * Kept off the public API so the `mlkit-translate` dependency stays an
 * implementation detail of [MlKitTranslator].
 */
internal interface NmtClient {
    /** Download the on-device model if not present; true on success. */
    suspend fun ensureModel(): Boolean

    /** Translate one text segment; never null. Throws on backend failure. */
    suspend fun translate(text: String): String

    /** Eagerly release the underlying ML Kit translator. */
    fun close()
}

/** Factory for [NmtClient]s keyed by BCP-47 language pair. */
internal fun interface NmtClientFactory {
    fun create(sourceBcp47: String, targetBcp47: String): NmtClient
}

/** Detects the source language of an arbitrary text snippet. */
internal interface LanguageIdentifier {
    /** BCP-47 code or `null` when ML Kit can't identify with confidence. */
    suspend fun identify(text: String): String?
    fun close()
}
