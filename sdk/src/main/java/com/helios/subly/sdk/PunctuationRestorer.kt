package com.helios.subly.sdk

import java.util.Locale

/**
 * Restores sentence-terminal punctuation and casing on ASR output that has
 * none.
 *
 * Streaming engines (Vosk, sherpa-onnx) emit lowercase, unpunctuated text.
 * That costs twice over: the captions are hard to read, and — less obviously —
 * [SentenceExtractor] only treats a boundary as complete when the text ends in
 * a terminator, so without one it can never split at a sentence. Captions then
 * break wherever the recogniser happened to endpoint, stranding clauses across
 * two on-screen chunks.
 *
 * The signal used here is the endpoint itself. Vosk and sherpa finalise on a
 * trailing pause, so **each final already marks a silence boundary** — the
 * strongest sentence cue available without a language model. Restoring a
 * terminator there costs nothing, needs no model or download, and lets the
 * extractor do the work it was written for.
 *
 * Deliberately conservative:
 *  - Text that is already punctuated is returned untouched, so engines that
 *    punctuate (whisper) are unaffected.
 *  - Only sentence-final marks are added. Commas, quotes, and apostrophes need
 *    a language model to place, and a wrong comma reads worse than none.
 *
 * The known failure is a speaker pausing mid-sentence, which yields a period
 * where a comma belongs. Against text with no punctuation at all, that trade
 * is worth making — but it is the reason this is a heuristic rather than a
 * replacement for a punctuation model.
 */
internal class PunctuationRestorer(languageTag: String) {

    /** Scripts written without inter-word spaces take full-width marks. */
    private val isUnspacedScript: Boolean =
        languageTag.lowercase(Locale.ROOT).substringBefore('-') in UNSPACED_LANGUAGES

    private val locale: Locale = Locale.forLanguageTag(languageTag)

    private val terminator: Char = if (isUnspacedScript) '。' else '.'

    /**
     * @param addTerminator false when the recogniser reports that the speaker
     *   had not finished — a whisper window cut at its length cap. Adding a
     *   full stop there makes sentence assembly break the caption mid-clause.
     * @param capitalise false when this text continues a sentence already
     *   pooled in the extractor, so the join does not read as
     *   "with clean wet Hands squeeze them into a ball".
     * @return [text] with a sentence terminator and leading capital, or [text]
     *   unchanged when it is empty or already punctuated.
     */
    fun restore(
        text: String,
        addTerminator: Boolean = true,
        capitalise: Boolean = true,
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.last() in SentenceExtractor.SENTENCE_TERMINATORS) return trimmed

        val cased =
            if (capitalise && !isUnspacedScript) capitaliseFirst(trimmed) else trimmed
        return if (addTerminator) cased + terminator else cased
    }

    /**
     * Upper-cases the letter that opens the sentence, leaving the rest alone
     * so an acronym or name the recogniser got right is never flattened.
     *
     * Opening punctuation is skipped — `"quoted` capitalises the `q`. A digit
     * is not: in `2019 was the year` the number *is* the sentence start, and
     * reaching past it would capitalise a word mid-sentence.
     */
    private fun capitaliseFirst(text: String): String {
        val i = text.indexOfFirst { it.isLetterOrDigit() }
        if (i < 0) return text
        val c = text[i]
        if (!c.isLetter() || c.isUpperCase()) return text
        return text.substring(0, i) + c.titlecase(locale) + text.substring(i + 1)
    }

    private companion object {
        val UNSPACED_LANGUAGES = setOf("zh", "ja", "ko", "th")
    }
}
