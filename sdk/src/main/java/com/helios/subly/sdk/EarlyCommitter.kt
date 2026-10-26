package com.helios.subly.sdk

import java.text.BreakIterator
import java.util.Locale

/**
 * Commits sentences from partial hypotheses, before the recogniser's final.
 *
 * A streaming recogniser finalises on a pause, and fast dialogue rarely
 * pauses: on the real-world Chinese clip it finalised every ~10 s, at the
 * run-on cap, so a line spoken at second 10 waited until second 19 to be
 * translated. But its partials are already punctuated (by the recogniser, or
 * the Chinese punctuator), and a sentence followed by more speech is as good
 * as committed. So:
 *  - [onPartial] returns the sentences of a hypothesis that are complete
 *    (end in a terminator, with speech after them) and unchanged since the
 *    previous partial, and remembers them;
 *  - [onFinal] removes what was committed early from the final's text, so it
 *    is translated once. When the recogniser revised that text in the
 *    meantime, sentences that still match it closely are removed too, and
 *    the rest is kept: a sentence translated twice is better than one lost.
 *
 * Partials and the final are given as the whole utterance including any
 * pooled text before it, so both sides are in the same coordinates.
 *
 * Not thread-safe; the session calls it from its ASR collector.
 */
internal class EarlyCommitter(locale: Locale) {

    private val boundary: BreakIterator = BreakIterator.getSentenceInstance(locale)

    /** Normalised text of everything committed early since the last final. */
    private var committed = ""

    /** Complete sentences seen in the previous partials, not yet committed, and how many times in a row. */
    private var lastCandidate: String? = null
    private var seen = 0

    /** The hypothesis stopped agreeing with what was committed; wait for the final. */
    private var diverged = false

    fun onPartial(hypothesis: String): List<String> {
        if (diverged) return emptyList()
        val start = offsetAfter(hypothesis, committed) ?: run {
            diverged = true
            lastCandidate = null
            return emptyList()
        }
        val end = lastCompleteSentenceEnd(hypothesis, start)
        if (end == null) {
            lastCandidate = null
            return emptyList()
        }
        val candidate = hypothesis.substring(start, end).trim()
        if (candidate.isEmpty() || candidate != lastCandidate) {
            lastCandidate = candidate.ifEmpty { null }
            seen = 1
            return emptyList()
        }
        if (++seen < STABLE_PARTIALS) return emptyList()
        lastCandidate = null
        seen = 0
        committed += normalise(candidate)
        return splitSentences(candidate)
    }

    /** [hypothesis] without what has been committed from it, for display as the live caption. */
    fun uncommitted(hypothesis: String): String =
        if (diverged) hypothesis else offsetAfter(hypothesis, committed)?.let { hypothesis.substring(it) } ?: hypothesis

    /** The final's text without what [onPartial] already committed. Resets for the next utterance. */
    fun onFinal(text: String): String {
        val done = committed
        committed = ""
        lastCandidate = null
        seen = 0
        diverged = false
        if (done.isEmpty()) return text

        offsetAfter(text, done)?.let { return text.substring(it).trimStart { !it.isLetterOrDigit() && !isOpeningMark(it) } }

        // Revised since: drop leading sentences that are close to what was
        // committed, in order; keep everything after the first that is not.
        var remaining = done
        val kept = ArrayList<String>()
        for (sentence in splitSentences(text)) {
            val n = normalise(sentence)
            if (kept.isEmpty() && remaining.isNotEmpty() && similar(n, remaining.take(n.length + n.length / 4))) {
                remaining = remaining.drop(n.length)
            } else {
                kept += sentence
            }
        }
        return joinSentences(kept)
    }

    /**
     * Index in [text] just past the characters whose letters and digits spell
     * [prefix], or null when [text] does not start with it.
     */
    private fun offsetAfter(text: String, prefix: String): Int? {
        if (prefix.isEmpty()) return 0
        var matched = 0
        for (i in text.indices) {
            val c = text[i]
            if (!c.isLetterOrDigit()) continue
            if (c.lowercaseChar() != prefix[matched]) return null
            matched++
            if (matched == prefix.length) {
                var j = i + 1
                while (j < text.length && !text[j].isLetterOrDigit() && !isOpeningMark(text[j])) j++
                return j
            }
        }
        return null
    }

    /**
     * End of the last sentence in [text] after [from] that ends in a
     * terminator and has speech after it, or null.
     */
    private fun lastCompleteSentenceEnd(text: String, from: Int): Int? {
        val tail = text.substring(from)
        boundary.setText(tail)
        var best: Int? = null
        var end = boundary.next()
        while (end != BreakIterator.DONE) {
            val before = tail.substring(0, end).trimEnd()
            val after = tail.substring(end)
            val following = after.count { it.isLetterOrDigit() }
            val needed = if (after.any { isUnspaced(it) }) MIN_FOLLOWING_UNSPACED else MIN_FOLLOWING_CHARS
            if (before.lastOrNull() in SentenceExtractor.SENTENCE_TERMINATORS && following >= needed) {
                best = from + end
            }
            end = boundary.next()
        }
        return best
    }

    private fun splitSentences(text: String): List<String> {
        boundary.setText(text)
        val out = ArrayList<String>()
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            start = end
            end = boundary.next()
        }
        return out
    }

    private fun joinSentences(sentences: List<String>): String = buildString {
        for (s in sentences) {
            if (isNotEmpty() && !isUnspaced(last()) && !isUnspaced(s.first())) append(' ')
            append(s)
        }
    }

    /** Character-bigram Dice similarity, high enough to call two renderings of a sentence the same. */
    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        fun grams(s: String) = (0 until s.length - 1).map { s.substring(it, it + 2) }.groupingBy { it }.eachCount()
        val ga = grams(a)
        val gb = grams(b)
        val overlap = ga.entries.sumOf { (k, v) -> minOf(v, gb[k] ?: 0) }
        val total = (a.length - 1).coerceAtLeast(1) + (b.length - 1).coerceAtLeast(1)
        return 2.0 * overlap / total >= SIMILAR
    }

    private fun normalise(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

    private fun isOpeningMark(c: Char) = c in "¿¡«“‘「『（(\"'"

    private fun isUnspaced(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA || c in "。！？，、"
    }

    private companion object {
        /**
         * Letters of speech needed after a terminator before the sentence
         * counts as complete: the punctuator (or recogniser) has seen what
         * follows, so the full stop is unlikely to move.
         */
        const val MIN_FOLLOWING_CHARS = 3

        /**
         * The same for Chinese and Japanese, where a character is a syllable
         * and the punctuator, which only sees the hypothesis, needs a few of
         * them to settle on a full stop.
         */
        const val MIN_FOLLOWING_UNSPACED = 4

        /**
         * Consecutive partials a complete sentence must appear in unchanged.
         * Partials arrive every ~200 ms, so this holds a sentence back ~0.4 s
         * for the recogniser to stop revising it.
         */
        const val STABLE_PARTIALS = 3

        const val SIMILAR = 0.6
    }
}
