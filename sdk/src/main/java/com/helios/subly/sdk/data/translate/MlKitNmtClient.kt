package com.helios.subly.sdk.data.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Real ML Kit-backed implementations of [NmtClient] and [LanguageIdentifier].
 * Kept here so unit tests of [MlKitTranslator] don't need to instantiate the
 * ML Kit SDK.
 */
internal object MlKitNmt {

    /**
     * Default factory: builds a [Translation.getClient] per pair.
     * Returns a no-op client when either language is not supported by ML Kit
     * so the engine degrades to pass-through instead of throwing.
     */
    val defaultFactory: NmtClientFactory = NmtClientFactory { source, target ->
        val srcCode = TranslateLanguage.fromLanguageTag(source)
        val tgtCode = TranslateLanguage.fromLanguageTag(target)
        if (srcCode == null || tgtCode == null) {
            UnsupportedNmtClient
        } else {
            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(srcCode)
                    .setTargetLanguage(tgtCode)
                    .build(),
            )
            object : NmtClient {
                override suspend fun ensureModel(): Boolean = runCatching {
                    // Wi-Fi-only by default; the Consumer UI can pre-warm via
                    // its own `RemoteModelManager` calls when the user opts in
                    // to metered downloads.
                    translator.downloadModelIfNeeded(
                        DownloadConditions.Builder().requireWifi().build(),
                    ).await()
                    true
                }.getOrDefault(false)

                override suspend fun translate(text: String): String =
                    translator.translate(text).await()

                override fun close() {
                    runCatching { translator.close() }
                }
            }
        }
    }

    /** Default ML Kit language identifier with a conservative confidence floor. */
    fun defaultLanguageIdentifier(): LanguageIdentifier {
        val client = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build(),
        )
        return object : LanguageIdentifier {
            override suspend fun identify(text: String): String? {
                if (text.isBlank()) return null
                return runCatching {
                    val tag = client.identifyLanguage(text).await()
                    if (tag == "und") null else tag
                }.getOrNull()
            }

            override fun close() {
                runCatching { client.close() }
            }
        }
    }
}

private val UnsupportedNmtClient = object : NmtClient {
    override suspend fun ensureModel(): Boolean = false
    override suspend fun translate(text: String): String = text
    override fun close() {}
}
