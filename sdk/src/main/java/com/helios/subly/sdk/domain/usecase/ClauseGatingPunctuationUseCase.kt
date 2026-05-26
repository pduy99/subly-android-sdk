package com.helios.subly.sdk.domain.usecase

import com.helios.subly.sdk.domain.model.TranslationPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * Clause-boundary gating use case.
 *
 * Intercepts the raw streaming ASR flow and decides which packets are forwarded
 * to the (expensive) NMT translation stage:
 *
 *  - **Interim hypotheses** (`isFinal=false`) pass through with the punctuated
 *    English text so the overlay can render live captions, but they are not
 *    re-marked as final and downstream NMT must skip them.
 *  - A clause is "committed" (re-emitted with `isFinal=true`) when one of:
 *      - a sentence-terminal token (`.`, `?`, `!`) appears in the punctuated text.
 *      - a comma `,` appears and >= [COMMA_MIN_WORDS] words are buffered.
 *      - >= [HARD_FLUSH_WORDS] words buffered without any boundary.
 *      - the recognizer reports an endpoint (`isFinal=true`).
 *
 * This is the Red-Flag-2 fix: prevents the autoregressive NMT decoder from
 * running on every interim word emission, which would peg the CPU.
 *
 * @param punctuate Function that adds punctuation to a raw transcript chunk.
 *   Typically delegates to [com.helios.subly.sdk.data.ai.SherpaOnnxPunctuation].
 */
class ClauseGatingPunctuationUseCase(
    private val punctuate: (String) -> String,
) {
    operator fun invoke(packets: Flow<TranslationPacket>): Flow<TranslationPacket> =
        packets.transform { raw ->
            val rawText = raw.text.trim()
            if (rawText.isEmpty()) return@transform

            val punctuated = runCatching { punctuate(rawText) }.getOrDefault(rawText)

            // ASR endpoint -> always commit clause for translation.
            if (raw.isFinal) {
                emit(raw.copy(text = punctuated, isFinal = true))
                return@transform
            }

            val terminator = punctuated.lastOrNull { it in SENTENCE_TERMINATORS }
            val wordCount = punctuated.split(WHITESPACE).count { it.isNotBlank() }

            val commit = when {
                terminator != null && terminator in HARD_TERMINATORS -> true
                terminator == ',' && wordCount >= COMMA_MIN_WORDS -> true
                wordCount >= HARD_FLUSH_WORDS -> true
                else -> false
            }

            emit(raw.copy(text = punctuated, isFinal = commit))
        }

    private companion object {
        val SENTENCE_TERMINATORS = setOf('.', '?', '!', ',')
        val HARD_TERMINATORS = setOf('.', '?', '!')
        val WHITESPACE = Regex("\\s+")
        const val COMMA_MIN_WORDS = 6
        const val HARD_FLUSH_WORDS = 12
    }
}
