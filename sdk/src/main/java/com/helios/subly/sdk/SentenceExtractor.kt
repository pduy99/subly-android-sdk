package com.helios.subly.sdk

import java.text.BreakIterator
import java.util.Locale

/**
 * Accumulates final ASR chunks and yields *complete sentences* for
 * translation.
 *
 * Why this exists: machine translation quality degrades sharply on sentence
 * fragments — translating "Today," and "I'm going to say one long English
 * sentence." separately produces worse output in both halves than translating
 * the whole sentence once. So the pipeline pools ASR finals here and only
 * sends complete sentences to the translator.
 *
 * Boundary rules:
 *  - Sentence boundaries come from [BreakIterator.getSentenceInstance]
 *    (ICU-backed on Android), which handles decimals ("3.5"), ellipses, and
 *    common abbreviations — not from naive punctuation scanning.
 *  - A boundary only counts as *complete* if the text before it ends with a
 *    sentence terminator (`.`, `!`, `?`, `…`). Commas never finalize.
 *  - Unterminated text longer than [maxWords] words is force-split — at the
 *    last comma inside the window if one exists past [minWordsBeforeCommaCut]
 *    words (clause boundary), otherwise at the word cap.
 *
 * Not thread-safe; callers synchronize externally.
 */
internal class SentenceExtractor(
    locale: Locale,
    private val maxWords: Int = MAX_WORDS_PER_SENTENCE,
    private val minWordsBeforeCommaCut: Int = MIN_WORDS_BEFORE_COMMA_CUT,
) {

    private val pool = StringBuilder()
    private val boundary: BreakIterator = BreakIterator.getSentenceInstance(locale)

    /**
     * Rolling record of the last [OVERLAP_WORDS] words appended (post-strip),
     * independent of the pool so de-overlap still works after a sentence has
     * been extracted and the pool drained. Used only for overlap detection.
     */
    private val recentWords = ArrayDeque<String>()

    val isEmpty: Boolean get() = pool.isBlank()

    /** Current pooled (incomplete) text — used as context for partials. */
    fun pending(): String = pool.toString().trim()

    fun append(chunk: String) {
        val trimmed = stripLeadingOverlap(chunk.trim())
        if (trimmed.isEmpty()) return
        if (pool.isNotEmpty() && !pool.endsWith(" ")) pool.append(' ')
        pool.append(trimmed)
        rememberTail(trimmed)
    }

    /**
     * Removes any leading words of [chunk] that duplicate the most recently
     * appended words. Chunked ASR (whisper) re-emits the overlap tail carried
     * across a max-length segment cut, and adjacent windows repeat boundary
     * phrases — both surface as the same words appearing at the end of one
     * final and the start of the next ("Tech guy. Tech guy?"). Collapsing them
     * here keeps the duplication out of the captions and the translator.
     *
     * Only multi-word overlaps (>= [MIN_OVERLAP_WORDS]) are stripped: a single
     * shared word is too common (legitimate repeats like "very very") to treat
     * as an artifact.
     */
    private fun stripLeadingOverlap(chunk: String): String {
        if (chunk.isEmpty() || recentWords.isEmpty()) return chunk
        val chunkWords = WORD.findAll(chunk).toList()
        if (chunkWords.isEmpty()) return chunk

        val maxK = minOf(OVERLAP_WORDS, recentWords.size, chunkWords.size)
        var matchedK = 0
        for (k in maxK downTo MIN_OVERLAP_WORDS) {
            val tail = recentWords.subList(recentWords.size - k, recentWords.size)
            var same = true
            for (i in 0 until k) {
                if (!wordsEqual(tail[i], chunkWords[i].value)) {
                    same = false
                    break
                }
            }
            if (same) {
                matchedK = k
                break
            }
        }
        if (matchedK == 0) return chunk
        // Drop the matched prefix; resume at the first word past it.
        val firstKept = chunkWords.getOrNull(matchedK) ?: return ""
        return chunk.substring(firstKept.range.first)
    }

    private fun rememberTail(text: String) {
        for (m in WORD.findAll(text)) recentWords.addLast(m.value)
        while (recentWords.size > OVERLAP_WORDS) recentWords.removeFirst()
    }

    /** Case/punctuation-insensitive word match for overlap detection. */
    private fun wordsEqual(a: String, b: String): Boolean {
        val na = normalizeWord(a)
        return na.isNotEmpty() && na == normalizeWord(b)
    }

    private fun normalizeWord(w: String): String =
        w.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() }

    /**
     * Removes and returns all complete sentences currently in the pool.
     * Incomplete trailing text stays pooled.
     */
    fun extractCompleted(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut(pool.toString()) ?: break
            val sentence = pool.substring(0, cut).trim()
            pool.delete(0, cut)
            if (sentence.isNotEmpty()) out.add(sentence)
        }
        return out
    }

    /**
     * Removes and returns everything left in the pool (complete or not).
     * Used by the idle flush: when no more ASR finals are coming, the
     * pooled remainder is the best sentence we will ever get.
     */
    fun drain(): String {
        val remainder = pool.toString().trim()
        pool.setLength(0)
        return remainder
    }

    private fun findCut(text: String): Int? {
        if (text.isBlank()) return null

        boundary.setText(text)
        val end = boundary.next()
        if (end != BreakIterator.DONE) {
            val candidate = text.substring(0, end)
            val last = candidate.trimEnd().lastOrNull()
            if (last != null && last in SENTENCE_TERMINATORS) return end
            // A mid-text ICU boundary without a terminator (rare for ASR
            // text — e.g. embedded newline): cut anyway so the pool can't
            // stall behind it.
            if (end < text.length) return end
        }

        // Single unterminated sentence: force-split at the word cap.
        return wordCapCut(text)
    }

    private fun wordCapCut(text: String): Int? {
        val words = WORD.findAll(text).toList()
        if (words.size >= maxWords) {
            val capEnd = words[maxWords - 1].range.last + 1
            // Prefer the last clause boundary (comma) inside the window over
            // a hard cut, as long as it isn't absurdly early.
            val minCommaIndex = words[minWordsBeforeCommaCut - 1].range.last
            val lastComma = text.lastIndexOf(',', startIndex = capEnd - 1)
            return if (lastComma > minCommaIndex) lastComma + 1 else capEnd
        }

        // Unspaced scripts (Japanese, Chinese, Thai, ...): whitespace "words"
        // are meaningless — a 300-char run counts as one. Cap by characters
        // instead, preferring the last ideographic/clause comma in the window.
        if (words.size <= 2 && text.trimEnd().length >= MAX_UNSPACED_CHARS) {
            val window = text.substring(0, MAX_UNSPACED_CHARS)
            val lastClause = window.lastIndexOfAny(UNSPACED_CLAUSE_MARKS)
            var cut = if (lastClause >= MIN_UNSPACED_CHARS) lastClause + 1 else MAX_UNSPACED_CHARS
            // Never split a surrogate pair.
            while (cut < text.length && text[cut].isLowSurrogate()) cut++
            return cut
        }

        return null
    }

    internal companion object {
        /**
         * Includes CJK full-width terminators ('。', '！', '？', '．') —
         * without them, Japanese/Chinese sentences never counted as complete
         * and the pool grew until the idle flush. The ideographic comma '、'
         * is deliberately NOT a terminator (same rule as ',').
         */
        val SENTENCE_TERMINATORS = setOf('.', '!', '?', '…', '。', '！', '？', '．')
        val WORD = Regex("\\S+")

        /** Char-based force-split for unspaced scripts (~1 long JA sentence). */
        const val MAX_UNSPACED_CHARS = 50
        const val MIN_UNSPACED_CHARS = 10
        val UNSPACED_CLAUSE_MARKS = charArrayOf('、', '，', ',')

        /**
         * Force-split threshold. 28 (was 20): benchmarks showed normal
         * spoken sentences routinely run 15-22 words, and a 21-word sentence
         * was getting comma-split right before its real terminator. The cap
         * should only catch pathological run-ons, and ML Kit comfortably
         * translates ~28-word sentences.
         */
        const val MAX_WORDS_PER_SENTENCE = 28
        const val MIN_WORDS_BEFORE_COMMA_CUT = 5

        /** Words of trailing history kept for de-overlap stitching. */
        const val OVERLAP_WORDS = 12

        /**
         * Minimum overlap length (words) that counts as a stitch artifact.
         * A single shared word is too common (legitimate repeats) to strip.
         */
        const val MIN_OVERLAP_WORDS = 2
    }
}
