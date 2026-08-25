package com.helios.subly.sdk

import java.util.Locale

/**
 * Removes the inter-word spaces some recognisers emit in scripts that are
 * written without them.
 *
 * Vosk and sherpa tokenise internally and join their output with spaces,
 * which is correct for English and wrong for Chinese, Japanese, Korean, and
 * Thai. Measured on the benchmark corpus, Vosk emitted 804 spaces across the
 * Japanese clips and 534 across the Chinese ones, against 20 and 34 in the
 * human references — text that reads as though every word were a sentence
 * fragment:
 *
 * ```
 * 俺 聞い た 情報 に よれ ば 当該 文書 は 国境 紛争 に 言及 する もの で
 * ```
 *
 * Only spaces *between two characters of the unspaced script* are removed. A
 * space next to Latin text, a digit, or punctuation is left alone, because
 * those are the places a space genuinely belongs — `USB メモリ`, `COVID 19`,
 * `2019 年` — and deleting them would corrupt exactly the tokens a reader
 * relies on most.
 */
internal class CjkSpacing(languageTag: String) {

    private val enabled: Boolean =
        languageTag.lowercase(Locale.ROOT).substringBefore('-') in UNSPACED_LANGUAGES

    fun collapse(text: String): String {
        if (!enabled || text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ') {
                // Look past a run of spaces at what sits on either side.
                var j = i
                while (j < text.length && text[j] == ' ') j++
                val before = out.lastOrNull()
                val after = text.getOrNull(j)
                if (before != null && after != null && isUnspaced(before) && isUnspaced(after)) {
                    i = j          // both sides are CJK: the space is an artefact
                    continue
                }
                out.append(' ')
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * True for characters that are written without spaces around them —
     * Han, Kana, Hangul, Thai, and the full-width punctuation that goes with
     * them. Deliberately excludes Latin letters and digits.
     */
    private fun isUnspaced(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                b == Character.UnicodeBlock.HIRAGANA ||
                b == Character.UnicodeBlock.KATAKANA ||
                b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                b == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                b == Character.UnicodeBlock.HANGUL_JAMO ||
                b == Character.UnicodeBlock.THAI ||
                b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    private companion object {
        /** Mirrors PunctuationRestorer.UNSPACED_LANGUAGES. */
        val UNSPACED_LANGUAGES = setOf("zh", "ja", "ko", "th")
    }
}
