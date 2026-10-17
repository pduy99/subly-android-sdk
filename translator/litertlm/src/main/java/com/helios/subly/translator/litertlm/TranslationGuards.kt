package com.helios.subly.translator.litertlm

/**
 * Sanity checks on an LLM's translation output.
 *
 * A per-pair NMT model like ML Kit either translates or errors. A general
 * instruction-tuned model has two extra failure modes that look like success:
 * it hands back the input verbatim, or it falls into a repetition loop. Both
 * reach the caption overlay as confident, well-formed nonsense unless
 * something checks.
 *
 * The checks below are adapted from Bao-Translate's `ValidationUtils`
 * (github.com/d4551/Bao-Translate, Apache-2.0), which hit the same two
 * failure modes running a small LLM on-device.
 */
internal object TranslationGuards {

    /**
     * True when the model echoed the source instead of translating it.
     *
     * Two things stop this firing on output that is legitimately unchanged.
     * It only applies when the languages actually differ, and it only applies
     * to a source of at least [MIN_WORDS_TO_JUDGE_ECHO] words: a caption that
     * is a bare proper noun, a number, or "OK." often *is* the same string in
     * the target language, and flagging that would fail the whole caption
     * over a correct translation. A four-word sentence handed back verbatim
     * is not a coincidence.
     *
     * Counting words has a consequence worth knowing: the check is inert for
     * sources in unspaced scripts. `CjkSpacing` collapses inter-word spaces
     * before a caption reaches the translator, so a Chinese or Japanese
     * sentence arrives as a single "word" and never reaches the comparison.
     * That is a gap rather than a bug — the failure mode is a missed echo,
     * never a correct translation thrown away — but do not read this guard as
     * covering every language.
     */
    fun isSourceEcho(
        translated: String,
        source: String,
        sourceTag: String,
        targetTag: String,
    ): Boolean {
        if (sourceTag.equals(targetTag, ignoreCase = true)) return false
        val a = translated.trim()
        val b = source.trim()
        // Two empty sides have nothing to echo. Kotlin's trim() is
        // Unicode-aware, so a whitespace-only input (NBSP, EM SPACE) lands
        // here rather than being reported as a verbatim echo.
        if (a.isEmpty() || b.isEmpty()) return false
        if (b.split(WHITESPACE).count { it.isNotEmpty() } < MIN_WORDS_TO_JUDGE_ECHO) return false
        return a.equals(b, ignoreCase = true)
    }

    /**
     * True when the output is a repetition loop rather than a translation.
     *
     * Two independent tests, because they catch different shapes:
     *  - a low unique/total word ratio catches long rambling loops,
     *  - an exact "N copies of a k-word unit" check catches short ones like
     *    `xin chào xin chào`, whose ratio (0.5) is nowhere near the
     *    threshold.
     */
    fun isDegenerate(translated: String): Boolean {
        if (translated.isBlank()) return true

        val words = translated.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS_TO_JUDGE) return false

        val uniqueRatio = words.toSet().size.toFloat() / words.size
        if (uniqueRatio < MIN_UNIQUE_RATIO) return true

        for (unitSize in 1..words.size / 2) {
            if (words.size % unitSize != 0) continue
            val unit = words.subList(0, unitSize)
            val repeats = (0 until words.size step unitSize).all { start ->
                words.subList(start, start + unitSize) == unit
            }
            if (repeats) return true
        }
        return false
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Removes Han characters the model left untranslated in a target that is
     * not written with them.
     *
     * Small LLMs translating from Chinese sometimes copy a word through
     * instead of translating it ("…ở nhà là vân thời小时." for Vietnamese,
     * observed on device). A reader of the target language cannot read it,
     * so it is dropped; retrying would cost another multi-second generation
     * for, usually, one word. Targets that do use Han characters (Chinese,
     * Japanese, Cantonese) are returned unchanged.
     *
     * @return the cleaned text, or [translated] itself when nothing leaked.
     */
    fun stripHanLeak(translated: String, targetTag: String): String {
        if (targetTag.substringBefore('-').lowercase() in HAN_TARGETS) return translated
        if (translated.none(::isHan)) return translated
        return translated
            .replace(HAN_RUN, " ")
            .replace(WHITESPACE, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .trim()
    }

    private fun isHan(ch: Char): Boolean = Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN

    /** Targets whose writing system includes Han characters. */
    private val HAN_TARGETS = setOf("zh", "ja", "yue")
    private val HAN_RUN = Regex("\\p{IsHan}+")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([.,!?;:…])")

    /**
     * Below this a verbatim echo is plausibly the right answer — see
     * [isSourceEcho].
     */
    private const val MIN_WORDS_TO_JUDGE_ECHO = 4

    /**
     * Below this, repetition is indistinguishable from a legitimately short
     * translation — "no no" is a real sentence in most languages.
     */
    private const val MIN_WORDS_TO_JUDGE = 3

    /** Under a third of the words being distinct is a loop, not a sentence. */
    private const val MIN_UNIQUE_RATIO = 0.3f
}
