package com.helios.subly.asr.whisper

/**
 * Collapses whisper repetition-loop hallucinations.
 *
 * Whisper (especially small models on short/truncated audio) degenerates
 * into loops like "if you do not want to, if you do not want to, …" or
 * "Today! Today! Today! …". The first occurrence of the phrase is usually
 * the correct transcription — so we keep it and drop the repeats, rather
 * than discarding the whole window.
 */
internal object RepetitionFilter {

    /** A phrase must appear this many times consecutively to be collapsed. */
    private const val MIN_REPEATS = 3

    /** Tokens at least this long get the char-level cycle pass. */
    private const val CHAR_CYCLE_MIN_LEN = 12

    private val WHITESPACE = Regex("\\s+")

    /**
     * Returns [text] with consecutive phrase repetitions collapsed to a
     * single occurrence, and degenerate character-run "words" (e.g.
     * "MMMMMMMM…") removed. Returns "" if nothing real remains.
     */
    fun collapse(text: String): String {
        if (text.isBlank()) return ""

        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()
        words.removeAll { it.length > 12 && isMostlyOneChar(it) }
        if (words.isEmpty()) return ""

        // Unspaced scripts (Japanese, Chinese, Thai, ...) arrive as one giant
        // "word", so the word-level pass below can't see their loops
        // ("よろしくお願いします" x17 is a single 170-char token). Collapse
        // repeating character cycles inside long tokens first.
        for (i in words.indices) {
            if (words[i].length >= CHAR_CYCLE_MIN_LEN) {
                words[i] = collapseCharCycles(words[i])
            }
        }

        // Normalized forms for comparison only — output keeps original words
        // (first occurrence wins, so "to," and "to" compare equal but the
        // emitted text is untouched).
        val norms = words.map { normalize(it) }.toMutableList()

        var period = 1
        while (period <= words.size / MIN_REPEATS) {
            var i = 0
            while (i + period * MIN_REPEATS <= words.size) {
                var reps = 1
                while (i + (reps + 1) * period <= words.size &&
                    blockEquals(norms, i, i + reps * period, period)
                ) reps++

                if (reps >= MIN_REPEATS) {
                    // The loop often ends mid-phrase ("… if you do not want
                    // to, if you"). Drop that partial continuation too.
                    val tailStart = i + reps * period
                    var tail = 0
                    while (tail < period && tailStart + tail < words.size &&
                        norms[tailStart + tail] == norms[i + tail]
                    ) tail++

                    var to = tailStart + tail
                    // The decode token cap can cut the loop mid-word, leaving
                    // a fragment of the next expected phrase word ("… grammar
                    // rules. and without memor"). Drop it as well.
                    if (tail < period && to < words.size) {
                        val frag = norms[to]
                        val expected = norms[i + tail]
                        if (frag.length >= 2 && frag.length < expected.length &&
                            expected.startsWith(frag)
                        ) to++
                    }

                    val from = i + period
                    repeat(to - from) {
                        words.removeAt(from)
                        norms.removeAt(from)
                    }
                    i += period
                } else {
                    i++
                }
            }
            period++
        }
        return words.joinToString(" ")
    }

    /**
     * Collapses cyclic character repetitions inside a single token:
     * "同じです!同じです!同じです!同" -> "同じです!". Same algorithm as the
     * word-level pass, operating on chars; a trailing truncated cycle prefix
     * (the "同") is dropped as part of the tail check. Non-repetitive text of
     * any length passes through unchanged.
     */
    internal fun collapseCharCycles(word: String): String {
        val chars = StringBuilder(word)
        var period = 1
        while (period <= chars.length / MIN_REPEATS) {
            var i = 0
            while (i + period * MIN_REPEATS <= chars.length) {
                var reps = 1
                while (i + (reps + 1) * period <= chars.length &&
                    regionEquals(chars, i, i + reps * period, period)
                ) reps++

                if (reps >= MIN_REPEATS) {
                    val tailStart = i + reps * period
                    var tail = 0
                    while (tail < period && tailStart + tail < chars.length &&
                        chars[tailStart + tail] == chars[i + tail]
                    ) tail++
                    chars.delete(i + period, tailStart + tail)
                    i += period
                } else {
                    i++
                }
            }
            period++
        }
        return chars.toString()
    }

    private fun regionEquals(chars: StringBuilder, a: Int, b: Int, len: Int): Boolean {
        for (k in 0 until len) {
            if (chars[a + k] != chars[b + k]) return false
        }
        return true
    }

    private fun blockEquals(norms: List<String>, a: Int, b: Int, len: Int): Boolean {
        for (k in 0 until len) {
            if (norms[a + k] != norms[b + k]) return false
        }
        return true
    }

    private fun normalize(word: String): String =
        word.lowercase().trim { !it.isLetterOrDigit() }

    private fun isMostlyOneChar(word: String): Boolean {
        if (word.isEmpty()) return false
        val top = word.groupingBy { it.lowercaseChar() }.eachCount().values.max()
        return top >= word.length * 0.8
    }
}
