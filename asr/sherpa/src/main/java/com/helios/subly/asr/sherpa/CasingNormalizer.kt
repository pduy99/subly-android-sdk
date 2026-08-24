package com.helios.subly.asr.sherpa

import java.util.Locale

/**
 * Normalises all-upper-case recogniser output to ordinary sentence case.
 *
 * The streaming Zipformer model ships an upper-case token vocabulary, so every
 * hypothesis arrives shouting: measured across 17 benchmark transcripts, 12,689
 * upper-case characters and not one lower-case. All caps is materially harder
 * to read than mixed case — it strips the word-shape cues readers rely on — and
 * on captions that a viewer scans in a couple of seconds, it is the single
 * largest readability defect this engine has.
 *
 * Conservative by construction: text containing any lower-case letter is left
 * exactly as it is, so a future model that cases its own output is never
 * flattened by this.
 *
 * What is unavoidably lost: acronyms and proper nouns. `NASA` and `NEWGATE`
 * are indistinguishable in an all-caps stream, so both come out lower-cased.
 * Recovering them needs a language model; sentence case is still much closer
 * to readable prose than the alternative.
 */
internal object CasingNormalizer {

    /**
     * @param atSentenceStart true only when [text] genuinely begins a sentence.
     *   Defaults to false because for a streaming recogniser it usually does
     *   not: endpoints fire on breath pauses, so most finals open mid-clause.
     *   Capitalising every one of them manufactures false sentence starts —
     *   measured as *worse* than leaving the text lower-case, since a stray
     *   capital reads as a new sentence and the reader re-parses.
     * @return [text] in sentence case, or unchanged if it is not all-caps.
     */
    fun normalise(text: String, atSentenceStart: Boolean = false): String {
        if (text.isEmpty()) return text
        if (!isAllCaps(text)) return text

        val lowered = restoreStandaloneI(text.lowercase(Locale.ROOT))
        return if (atSentenceStart) capitaliseSentenceStart(lowered) else lowered
    }

    /** True when the text has at least one letter and none of them are lower. */
    private fun isAllCaps(text: String): Boolean {
        var sawLetter = false
        for (c in text) {
            if (c.isLowerCase()) return false
            if (c.isLetter()) sawLetter = true
        }
        return sawLetter
    }

    /**
     * English "I" is the one word whose capital survives lower-casing, and it
     * is frequent enough that missing it reads as an error. Covers the bare
     * pronoun and its contractions ("i'm", "i'll").
     */
    private fun restoreStandaloneI(text: String): String =
        STANDALONE_I.replace(text) { m -> "I" + m.value.substring(1) }

    private fun capitaliseSentenceStart(text: String): String {
        val i = text.indexOfFirst { it.isLetterOrDigit() }
        if (i < 0) return text
        val c = text[i]
        if (!c.isLetter()) return text
        return text.substring(0, i) + c.uppercaseChar() + text.substring(i + 1)
    }

    /** `i` as a whole word, optionally with a contraction suffix. */
    private val STANDALONE_I = Regex("""\bi(?='|\b)""")
}
