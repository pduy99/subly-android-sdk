package com.helios.subly.sdk.domain.usecase

import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * Behaviour matrix:
 *  - **Pass-through**: when the detected source equals the requested target
 *    (or the source is non-translatable), the original packet is forwarded
 *    untouched, with `targetLanguageCode` rewritten to the detected source.
 *    This is what the PRD calls a "degraded" packet from the consumer side.
 *  - **Unknown source**: when the transcriber doesn't surface a source code
 *    (e.g. `"auto"` placeholder while Whisper JNI doesn't yet expose
 *    `whisper_full_lang_id`), the use case asks the translator's source
 *    identifier; if still unknown, it pass-throughs degraded.
 *  - **Translate**: otherwise it streams the text through
 *    [TranslatorRepository.translate] and emits a packet with the translated
 *    text + the requested target code.
 */
class TranslatePacketUseCase(
    private val translator: TranslatorRepository,
) {
    operator fun invoke(
        packets: Flow<TranslationPacket>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> = packets.transform { raw ->
        val target = config.targetLanguageCode
        val text = raw.text
        if (text.isBlank()) {
            return@transform
        }

        val source = raw.sourceLanguageCode
            ?.takeUnless { it.equals(AUTO, ignoreCase = true) }
            ?: translator.identifySource(text)

        // No identifiable source -> degraded pass-through with the original
        // transcript. Consumer overlays can still render it (probably in the
        // original language) instead of dropping the segment.
        if (source == null) {
            emit(raw.copy(targetLanguageCode = raw.sourceLanguageCode ?: AUTO))
            return@transform
        }

        if (source.equals(target, ignoreCase = true)) {
            emit(raw.copy(sourceLanguageCode = source, targetLanguageCode = source))
            return@transform
        }

        translator.translate(text, source, target).collect { translated ->
            val passedThrough = translated === text
            emit(
                raw.copy(
                    text = translated,
                    sourceLanguageCode = source,
                    // If the translator silently passed through (failure or
                    // unsupported pair), surface that to the consumer by
                    // marking the packet as still-source-language.
                    targetLanguageCode = if (passedThrough) source else target,
                ),
            )
        }
    }

    private companion object {
        const val AUTO = "auto"
    }
}
