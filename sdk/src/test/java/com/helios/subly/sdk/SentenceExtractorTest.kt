package com.helios.subly.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Note: on-device, BreakIterator is ICU-backed and additionally handles
 * common abbreviations ("Mr. English" does not split). These tests stick to
 * UAX #29 behavior that holds on both the JVM and Android.
 */
class SentenceExtractorTest {

    private fun extractor() = SentenceExtractor(Locale.ENGLISH)

    @Test
    fun `comma does not finalize a sentence`() {
        val e = extractor()
        e.append("Today, I'm going to say one long English sentence.")
        assertEquals(
            listOf("Today, I'm going to say one long English sentence."),
            e.extractCompleted()
        )
        assertTrue(e.isEmpty)
    }

    @Test
    fun `comma-only text stays pooled`() {
        val e = extractor()
        e.append("Today, I'm going to say")
        assertEquals(emptyList<String>(), e.extractCompleted())
        assertEquals("Today, I'm going to say", e.pending())
    }

    @Test
    fun `two sentences extracted separately`() {
        val e = extractor()
        e.append("First sentence here. Second sentence too.")
        assertEquals(
            listOf("First sentence here.", "Second sentence too."),
            e.extractCompleted()
        )
        assertTrue(e.isEmpty)
    }

    @Test
    fun `unterminated remainder stays pooled`() {
        val e = extractor()
        e.append("This is complete. And this trails off")
        assertEquals(listOf("This is complete."), e.extractCompleted())
        assertEquals("And this trails off", e.pending())
    }

    @Test
    fun `ellipsis does not produce junk dot captions`() {
        val e = extractor()
        e.append("And then... You will see.")
        val out = e.extractCompleted()
        // Must never emit "." or ".." fragments (the old indexOfAny bug).
        assertTrue(out.none { it.trim('.', ' ').isEmpty() })
        assertEquals("And then... You will see.", out.joinToString(" "))
        assertTrue(e.isEmpty)
    }

    @Test
    fun `trailing ellipsis extracts as complete`() {
        val e = extractor()
        e.append("I'm going to say one long...")
        assertEquals(listOf("I'm going to say one long..."), e.extractCompleted())
    }

    @Test
    fun `decimal number does not split`() {
        val e = extractor()
        e.append("It costs 3.5 million dollars today.")
        assertEquals(
            listOf("It costs 3.5 million dollars today."),
            e.extractCompleted()
        )
    }

    @Test
    fun `question and exclamation terminate`() {
        val e = extractor()
        e.append("Is it hard? Not at all!")
        assertEquals(listOf("Is it hard?", "Not at all!"), e.extractCompleted())
    }

    @Test
    fun `word cap cuts at last comma clause boundary`() {
        val e = SentenceExtractor(Locale.ENGLISH, maxWords = 20)
        // 22 unterminated words, commas after word 7 and word 15.
        e.append(
            "w1 w2 w3 w4 w5 w6 w7, w8 w9 w10 w11 w12 w13 w14 w15, " +
                    "w16 w17 w18 w19 w20 w21 w22"
        )
        val out = e.extractCompleted()
        assertEquals(listOf("w1 w2 w3 w4 w5 w6 w7, w8 w9 w10 w11 w12 w13 w14 w15,"), out)
        assertEquals("w16 w17 w18 w19 w20 w21 w22", e.pending())
    }

    @Test
    fun `word cap without comma cuts at cap`() {
        val e = SentenceExtractor(Locale.ENGLISH, maxWords = 20)
        e.append((1..23).joinToString(" ") { "w$it" })
        val out = e.extractCompleted()
        assertEquals(listOf((1..20).joinToString(" ") { "w$it" }), out)
        assertEquals("w21 w22 w23", e.pending())
    }

    @Test
    fun `default cap does not split a 21-word sentence before its terminator`() {
        val e = extractor()
        // Regression: with the cap at 20, this exact sentence was comma-split
        // one word before its real boundary.
        e.append(
            "If you do not watch the video until the end, you may miss the " +
                    "full method used to build longer"
        )
        assertEquals(emptyList<String>(), e.extractCompleted())
        e.append("English sentences.")
        assertEquals(
            listOf(
                "If you do not watch the video until the end, you may miss the " +
                        "full method used to build longer English sentences."
            ),
            e.extractCompleted()
        )
    }

    @Test
    fun `under cap unterminated extracts nothing`() {
        val e = extractor()
        e.append("you may miss the full method used to build longer")
        assertEquals(emptyList<String>(), e.extractCompleted())
        assertEquals("you may miss the full method used to build longer", e.pending())
    }

    // ---- CJK (unspaced) ---------------------------------------------------

    private fun japaneseExtractor() = SentenceExtractor(Locale.JAPANESE)

    @Test
    fun `japanese full stop terminates a sentence`() {
        val e = japaneseExtractor()
        e.append("おはようございます。")
        assertEquals(listOf("おはようございます。"), e.extractCompleted())
        assertTrue(e.isEmpty)
    }

    @Test
    fun `japanese two sentences extract separately`() {
        val e = japaneseExtractor()
        e.append("おはようございます。今日は良い天気です。")
        assertEquals(
            listOf("おはようございます。", "今日は良い天気です。"),
            e.extractCompleted()
        )
    }

    @Test
    fun `fullwidth question and exclamation terminate`() {
        val e = japaneseExtractor()
        e.append("おいくつですか？二十歳です！")
        assertEquals(listOf("おいくつですか？", "二十歳です！"), e.extractCompleted())
    }

    @Test
    fun `unterminated japanese stays pooled`() {
        val e = japaneseExtractor()
        e.append("はい今日から働くことになりました佐藤優人")
        assertEquals(emptyList<String>(), e.extractCompleted())
        assertEquals("はい今日から働くことになりました佐藤優人", e.pending())
    }

    @Test
    fun `unspaced char cap cuts long unterminated runs at clause comma`() {
        val e = japaneseExtractor()
        val clauseA = "あ".repeat(30) + "、"   // 31 chars, comma inside window
        val clauseB = "い".repeat(40)          // no terminator
        e.append(clauseA + clauseB)
        val out = e.extractCompleted()
        assertEquals(listOf(clauseA), out)
        assertEquals(clauseB, e.pending())
    }

    @Test
    fun `unspaced char cap hard-cuts without clause comma`() {
        val e = japaneseExtractor()
        e.append("あ".repeat(70))
        val out = e.extractCompleted()
        assertEquals(listOf("あ".repeat(50)), out)
        assertEquals("あ".repeat(20), e.pending())
    }

    @Test
    fun `drain returns remainder and empties pool`() {
        val e = extractor()
        e.append("trailing words without punctuation")
        assertEquals("trailing words without punctuation", e.drain())
        assertTrue(e.isEmpty)
        assertEquals("", e.drain())
    }

    @Test
    fun `append joins chunks with single space`() {
        val e = extractor()
        e.append("first chunk")
        e.append("second chunk.")
        assertEquals(listOf("first chunk second chunk."), e.extractCompleted())
    }
}
