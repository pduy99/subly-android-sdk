package com.helios.subly.sdk

/**
 * Splits over-long Chinese and Japanese captions into readable ones.
 *
 * A recogniser that ends a final only at a pause produces run-ons in fast
 * dialogue: on a measured clip, seven subtitle lines arrived as one
 * 52-character "sentence". That is too long to read as a caption and slow to
 * translate (6.7 s for that one), and a translator given it whole produced
 * word salad. Splitting prefers clause punctuation (，、；：); with none, an
 * extreme run is halved, which can cut a word but loses less than the run-on.
 *
 * Other languages are returned unchanged: their sentence assembly already
 * splits on terminators, and character counts mean nothing there.
 */
internal class CjkSentenceSplitter(languageTag: String) {

    private val enabled = languageTag.substringBefore('-').lowercase() in CJK

    fun split(sentence: String): List<String> {
        if (!enabled || sentence.length <= MAX_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_CHARS) {
            val cut = clauseCut(rest) ?: if (rest.length > HARD_MAX_CHARS) rest.length / 2 else break
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotEmpty() }
    }

    /** End index of the last clause boundary within [MAX_CHARS], not too early. */
    private fun clauseCut(text: String): Int? {
        for (i in minOf(MAX_CHARS, text.length - 1) downTo MIN_PART_CHARS) {
            if (text[i - 1] in CLAUSE_MARKS) return i
        }
        return null
    }

    private companion object {
        val CJK = setOf("zh", "ja", "yue")
        val CLAUSE_MARKS = setOf('，', '、', '；', '：', ',', ';')

        /**
         * A subtitle line's worth. Longer goes looking for a clause break:
         * with punctuation restored, Chinese dialogue arrives as one sentence
         * of comma-separated lines, and the commas are where the lines are.
         */
        const val MAX_CHARS = 18

        /** With no clause break, only a run past this is cut blind. */
        const val HARD_MAX_CHARS = 30

        /** A split never leaves a fragment shorter than this in front. */
        const val MIN_PART_CHARS = 6
    }
}
