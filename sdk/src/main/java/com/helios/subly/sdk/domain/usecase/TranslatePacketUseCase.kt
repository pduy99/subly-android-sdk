package com.helios.subly.sdk.domain.usecase

import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatePacketUseCase(
    private val translator: TranslatorRepository,
) {
    operator fun invoke(
        packets: Flow<TranslationPacket>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> = packets.flatMapMerge { raw ->
        flow {
            val target = config.targetLanguageCode
            val text = raw.text
            if (text.isBlank()) {
                return@flow
            }

            // Interim hypothesis (gate has not committed a clause yet) - drop
            // entirely. Forwarding the raw source-language text would let
            // English interims overwrite the just-emitted Vietnamese commit
            // within a frame, making the overlay look like translation never
            // ran. The clause gate commits on `.?!`, on commas past 6 words,
            // on 12-word hard-flush, and on every ASR endpoint, so caption
            // cadence remains tight without firing NMT per-word.
            if (!raw.isFinal) {
                return@flow
            }

            val source = raw.sourceLanguageCode
                ?.takeUnless { it.equals(AUTO, ignoreCase = true) }
                ?: translator.identifySource(text)

            // No identifiable source -> degraded pass-through with the original
            // transcript. Consumer overlays can still render it (probably in the
            // original language) instead of dropping the segment.
            if (source == null) {
                emit(raw.copy(targetLanguageCode = raw.sourceLanguageCode ?: AUTO))
                return@flow
            }

            if (source.equals(target, ignoreCase = true)) {
                emit(raw.copy(sourceLanguageCode = source, targetLanguageCode = source))
                return@flow
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
    }

    private companion object {
        const val AUTO = "auto"
    }
}
