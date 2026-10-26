package com.helios.subly.sdk.streaming

import java.util.Locale

/**
 * Decides when a growing ASR hypothesis has enough settled text to translate.
 *
 * Two questions, answered separately:
 *
 * 1. **Is the text stable?** A partial is a guess and gets revised. A unit
 *    (word, or character for scripts written without spaces) counts as stable
 *    only once two consecutive hypotheses agree on it: the "local agreement"
 *    policy used by streaming Whisper. Translating an unstable word risks
 *    showing a translation the recogniser takes back a moment later.
 *
 * 2. **Is the stable text a translatable unit?** This is the wait-k part, and
 *    it depends on the source language's word order. An SVO source translated
 *    to an SVO target can be cut every [WaitKPolicy.k] words: "the man / bought
 *    a car" translates piece by piece. An SOV source cannot. In "watashi wa
 *    ringo o taberu" the verb comes last, and a translation of "watashi wa
 *    ringo o" alone has to invent one. So for SOV sources nothing is committed
 *    until a clause boundary: punctuation, or a verb-final marker the policy
 *    recognises.
 *
 * Continuous speech with no boundary is capped by [WaitKPolicy.maxWaitUnits]:
 * past that many stable uncommitted units the buffer commits anyway, trading
 * some grammar risk for bounded latency. [forcedCommits] counts how often
 * that happened.
 *
 * Not thread-safe; one pipeline drives one buffer.
 */
class WaitKBuffer(private val policy: WaitKPolicy) {

    private var previousUnits: List<String> = emptyList()
    private var committedUnits: List<String> = emptyList()

    /** Source text committed so far in this sentence. */
    val committed: String get() = joinUnits(committedUnits)

    var forcedCommits: Int = 0
        private set

    /**
     * Feeds the latest full hypothesis for the current sentence.
     *
     * @return newly committed source text, or null when nothing new is ready.
     */
    fun onPartial(hypothesis: String): String? {
        val units = tokenize(hypothesis)
        val stableCount = agreedPrefixLength(previousUnits, units)
        previousUnits = units
        if (!policy.drafts) return null

        // A revision that rewrites text already committed cannot be taken
        // back: the translation is on screen. Committed text stays, and the
        // final will be checked for the divergence (see onFinal).
        if (stableCount <= committedUnits.size) return null
        if (!agreedPrefixMatchesCommitted(units)) return null

        val commitEnd = commitBoundary(units, from = committedUnits.size, stableEnd = stableCount)
            ?: return null
        val delta = units.subList(committedUnits.size, commitEnd)
        committedUnits = units.subList(0, commitEnd).toList()
        return joinUnits(delta)
    }

    /**
     * Ends the sentence with its final text and resets for the next one.
     *
     * @return [FinalDelta.Continue] with the text after what was committed,
     *   or [FinalDelta.Diverged] when the final no longer starts with the
     *   committed text, in which case the caller should translate the whole
     *   final afresh.
     */
    fun onFinal(sentence: String): FinalDelta {
        val finalUnits = tokenize(sentence)
        val committedNormalized = committedUnits.map(::normalize).filter { it.isNotEmpty() }

        var matched = 0
        var index = 0
        while (index < finalUnits.size && matched < committedNormalized.size) {
            val unit = normalize(finalUnits[index])
            if (unit.isNotEmpty()) {
                if (unit != committedNormalized[matched]) break
                matched++
            }
            index++
        }

        val result = if (matched < committedNormalized.size) {
            FinalDelta.Diverged
        } else {
            // Punctuation the final added right after the committed text
            // belongs to the committed part, not the start of the delta.
            while (index < finalUnits.size && normalize(finalUnits[index]).isEmpty()) index++
            FinalDelta.Continue(joinUnits(finalUnits.subList(index, finalUnits.size)))
        }
        reset()
        return result
    }

    /** Drops all state for the current sentence. */
    fun reset() {
        previousUnits = emptyList()
        committedUnits = emptyList()
    }

    private fun agreedPrefixMatchesCommitted(units: List<String>): Boolean =
        committedUnits.indices.all { i -> i < units.size && normalize(units[i]) == normalize(committedUnits[i]) }

    /**
     * End index (exclusive) of the text to commit, or null to keep waiting.
     */
    private fun commitBoundary(units: List<String>, from: Int, stableEnd: Int): Int? {
        // Latest clause boundary inside the stable region wins: commit as
        // much complete clause as is available.
        for (i in stableEnd - 1 downTo from) {
            if (endsClause(units, i)) return i + 1
        }
        val pending = stableEnd - from
        if (policy.order == WordOrder.SVO && pending >= policy.k) return stableEnd
        if (pending >= policy.maxWaitUnits) {
            forcedCommits++
            return stableEnd
        }
        return null
    }

    private fun endsClause(units: List<String>, index: Int): Boolean {
        val unit = units[index]
        if (unit.isNotEmpty() && unit.last() in CLAUSE_PUNCTUATION) return true
        if (normalize(unit) in policy.phraseMarkers) return true
        if (policy.verbFinalMarkers.isEmpty()) return false
        // Markers can span units ("ま" + "す" in unspaced Japanese), so match
        // against the normalized text up to and including this unit.
        val tail = buildString {
            for (i in maxOf(0, index - MARKER_LOOKBACK_UNITS)..index) append(normalize(units[i]))
        }
        return policy.verbFinalMarkers.any { tail.endsWith(it) }
    }

    private fun agreedPrefixLength(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && normalize(a[n]) == normalize(b[n])) n++
        return n
    }

    private fun normalize(unit: String): String =
        unit.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    companion object {
        /** Ends a clause wherever it appears at the end of a unit. */
        val CLAUSE_PUNCTUATION: Set<Char> = setOf(
            '.', '!', '?', ',', ';', ':', '…',
            '。', '！', '？', '，', '、', '；', '：', '．',
        )

        /** Longest verb-final marker is a few characters; units are at least one. */
        private const val MARKER_LOOKBACK_UNITS = 4

        /**
         * Splits text into units: whitespace-separated words, with any run of
         * characters from scripts written without spaces (Han, kana, Thai, …)
         * split into single characters.
         */
        fun tokenize(text: String): List<String> {
            val units = ArrayList<String>()
            for (word in text.trim().split(WHITESPACE)) {
                if (word.isEmpty()) continue
                val current = StringBuilder()
                for (ch in word) {
                    if (isUnspacedScript(ch)) {
                        if (current.isNotEmpty()) {
                            units.add(current.toString())
                            current.clear()
                        }
                        units.add(ch.toString())
                    } else if (!ch.isLetterOrDigit() && current.isEmpty() && units.isNotEmpty() &&
                        isUnspacedScript(units.last().last())
                    ) {
                        // Punctuation after a CJK character attaches to it, so
                        // "食べる。" ends a clause on the unit "る。".
                        units[units.size - 1] = units.last() + ch
                    } else {
                        current.append(ch)
                    }
                }
                if (current.isNotEmpty()) units.add(current.toString())
            }
            return units
        }

        /** Rebuilds text from units, without spaces between unspaced-script characters. */
        fun joinUnits(units: List<String>): String = buildString {
            for ((i, unit) in units.withIndex()) {
                if (i > 0) {
                    val prev = units[i - 1]
                    val unspacedJoin = isUnspacedScript(prev.first()) && isUnspacedScript(unit.first())
                    if (!unspacedJoin) append(' ')
                }
                append(unit)
            }
        }

        private fun isUnspacedScript(ch: Char): Boolean {
            val block = Character.UnicodeScript.of(ch.code)
            return block == Character.UnicodeScript.HAN ||
                block == Character.UnicodeScript.HIRAGANA ||
                block == Character.UnicodeScript.KATAKANA ||
                block == Character.UnicodeScript.THAI ||
                block == Character.UnicodeScript.LAO ||
                block == Character.UnicodeScript.KHMER ||
                block == Character.UnicodeScript.MYANMAR
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** Result of closing a sentence in [WaitKBuffer.onFinal]. */
sealed interface FinalDelta {
    /** The final extends what was committed by [delta] (possibly empty). */
    data class Continue(val delta: String) : FinalDelta

    /** The recogniser revised committed text; re-translate the whole final. */
    data object Diverged : FinalDelta
}

enum class WordOrder { SVO, SOV }

/**
 * Wait-k parameters for one source language.
 *
 * @property k stable units an SVO source must accumulate before a commit
 *   without punctuation. Smaller is lower latency and more fragmentation.
 * @property maxWaitUnits forced-commit cap for continuous speech with no
 *   clause boundary, the worst-case latency bound.
 * @property verbFinalMarkers normalized (lowercase, letters and digits only)
 *   endings that close a clause in an SOV language.
 * @property phraseMarkers whole units (normalized) that close a noun phrase,
 *   such as Japanese case particles. Committing there gives the reader a
 *   draft before the verb arrives; [restructureOnFinal] then fixes the order.
 * @property drafts whether to commit pieces before the final at all. Off,
 *   every sentence is translated in one generation once it is final (still
 *   off the recogniser's coroutine, still streamed token by token).
 */
data class WaitKPolicy(
    val order: WordOrder,
    val k: Int,
    val maxWaitUnits: Int,
    val verbFinalMarkers: List<String> = emptyList(),
    val phraseMarkers: Set<String> = emptySet(),
    val drafts: Boolean = true,
) {
    /**
     * Whether a sentence's final translation must be generated whole rather
     * than assembled from its pieces. Pieces of an SOV sentence are
     * translated in source order ("I" / "apples" / "eat"), which is not the
     * order an SVO target needs; the pieces serve as live drafts and the
     * final restructures the sentence.
     */
    val restructureOnFinal: Boolean get() = order == WordOrder.SOV

    init {
        require(k >= 1) { "k must be at least 1, was $k" }
        require(maxWaitUnits >= k) { "maxWaitUnits ($maxWaitUnits) must be >= k ($k)" }
    }

    companion object {
        /**
         * Defaults per source language. Unspaced scripts count characters, so
         * their limits are larger than the word-based ones.
         */
        fun forLanguage(languageTag: String): WaitKPolicy =
            when (languageTag.substringBefore('-').lowercase(Locale.ROOT)) {
                // Polite and plain verb endings, in kana and in romaji. Plain
                // dictionary forms (-ru, -u) are not listed: in kana they end
                // countless non-verbs, and a false boundary is what this
                // buffer exists to prevent.
                "ja" -> WaitKPolicy(
                    order = WordOrder.SOV, k = 6, maxWaitUnits = 24,
                    verbFinalMarkers = listOf(
                        "ます", "ました", "ません", "ませんでした", "でした", "です", "ましょう",
                        "masu", "mashita", "masen", "desu", "deshita", "mashou",
                    ),
                    // Case particles that end a noun phrase. In kana only を:
                    // は, が, に and で also occur inside words (こんにちは),
                    // and a false phrase end splits a word. Romaji particles
                    // are separate words, so all are safe. の is excluded in
                    // both: it joins nouns rather than ending a phrase.
                    phraseMarkers = setOf("を", "wa", "o", "wo", "ga", "ni", "de", "e", "mo"),
                )
                // Declarative/polite/interrogative sentence endings.
                "ko" -> WaitKPolicy(
                    order = WordOrder.SOV, k = 4, maxWaitUnits = 10,
                    verbFinalMarkers = listOf("니다", "니까", "어요", "아요", "해요", "에요", "예요", "까요"),
                )
                "tr", "hi", "ur", "fa", "bn", "ta", "te", "mr", "my", "am" ->
                    WaitKPolicy(order = WordOrder.SOV, k = 4, maxWaitUnits = 10)
                "zh", "th", "lo", "km" -> WaitKPolicy(order = WordOrder.SVO, k = 6, maxWaitUnits = 20)
                else -> WaitKPolicy(order = WordOrder.SVO, k = 4, maxWaitUnits = 12)
            }
    }
}
