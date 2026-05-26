package com.helios.subly.sdk.internal

import android.content.Context
import com.helios.subly.sdk.data.translate.OnnxNmtTranslator
import com.helios.subly.sdk.domain.repository.DownloadResult
import com.helios.subly.sdk.domain.repository.SublyModelRegistry
import com.helios.subly.sdk.domain.repository.TranslatorRepository

/**
 * Default [SublyModelRegistry]. Backed by a private [TranslatorRepository]
 * instance for NMT model presence checks.
 */
internal class DefaultSublyModelRegistry(
    private val context: Context,
    private val translator: TranslatorRepository = OnnxNmtTranslator(context),
) : SublyModelRegistry {

    override suspend fun ensureTranslationPair(
        sourceBcp47: String,
        targetBcp47: String,
    ): DownloadResult = translator.ensureModel(sourceBcp47, targetBcp47)

    private companion object {
        const val TAG = "SublyModelRegistry"
    }
}

