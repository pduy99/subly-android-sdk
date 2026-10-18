package com.helios.subly.sdk.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaitKBufferTest {

    @Test
    fun `SOV romaji commits noun phrases at particles but never an unheard verb`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("ja"))

        assertNull(buffer.onPartial("Watashi wa"))
        assertEquals("Watashi wa", buffer.onPartial("Watashi wa ringo o"))
        // "taberu." is the first hypothesis carrying it, so it is not yet
        // agreed on: only the object phrase before it commits.
        assertEquals("ringo o", buffer.onPartial("Watashi wa ringo o taberu."))
        assertEquals("Watashi wa ringo o", buffer.committed)

        assertEquals(FinalDelta.Continue("taberu."), buffer.onFinal("Watashi wa ringo o taberu."))
    }

    @Test
    fun `kana particles other than wo never split a word`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("ja"))

        // は inside こんにちは must not end a phrase.
        assertNull(buffer.onPartial("こんにちは今日"))
        assertNull(buffer.onPartial("こんにちは今日は"))
        assertEquals("", buffer.committed)
    }

    @Test
    fun `SOV policies restructure on final and SVO policies do not`() {
        assertEquals(true, WaitKPolicy.forLanguage("ja").restructureOnFinal)
        assertEquals(true, WaitKPolicy.forLanguage("tr").restructureOnFinal)
        assertEquals(false, WaitKPolicy.forLanguage("en").restructureOnFinal)
    }

    @Test
    fun `SOV commits at a polite verb ending once two hypotheses agree`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("ja"))

        assertNull(buffer.onPartial("Watashi wa ringo o tabemasu"))
        assertEquals(
            "Watashi wa ringo o tabemasu",
            buffer.onPartial("Watashi wa ringo o tabemasu soshite"),
        )
        assertEquals(
            FinalDelta.Continue("soshite mizu o nomimasu."),
            buffer.onFinal("Watashi wa ringo o tabemasu, soshite mizu o nomimasu."),
        )
    }

    @Test
    fun `unspaced Japanese is split into characters and commits at ます`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("ja"))

        assertNull(buffer.onPartial("私はりんごを食べます"))
        assertEquals("私はりんごを食べます", buffer.onPartial("私はりんごを食べますそして"))
    }

    @Test
    fun `SVO commits every k stable words`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("en"))

        assertNull(buffer.onPartial("the man who"))
        assertNull(buffer.onPartial("the man who lives"))
        assertEquals("the man who lives", buffer.onPartial("the man who lives next"))
        assertEquals("the man who lives", buffer.committed)
    }

    @Test
    fun `punctuation commits a clause shorter than k`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("en"))

        assertNull(buffer.onPartial("Yes, I"))
        assertEquals("Yes,", buffer.onPartial("Yes, I think"))
    }

    @Test
    fun `unstable revisions are not committed`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("en"))

        assertNull(buffer.onPartial("I scream for"))
        // Recogniser revised the first word: agreement restarts from zero.
        assertNull(buffer.onPartial("ice cream for you"))
        assertEquals("ice cream for you", buffer.onPartial("ice cream for you and"))
    }

    @Test
    fun `continuous SOV speech without a boundary is force-committed at the cap`() {
        val policy = WaitKPolicy(order = WordOrder.SOV, k = 2, maxWaitUnits = 5)
        val buffer = WaitKBuffer(policy)
        val words = "bir iki uc dort bes alti yedi".split(' ')

        var committed: String? = null
        for (n in 1..words.size) {
            committed = buffer.onPartial(words.take(n).joinToString(" ")) ?: committed
        }
        assertEquals("bir iki uc dort bes", committed)
        assertEquals(1, buffer.forcedCommits)
    }

    @Test
    fun `final that rewrites committed text diverges`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("en"))
        buffer.onPartial("the man who lives")
        buffer.onPartial("the man who lives next")

        assertEquals(FinalDelta.Diverged, buffer.onFinal("A man who lives next door."))
    }

    @Test
    fun `final punctuation and capitals still match committed text`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("en"))
        buffer.onPartial("the man who lives")
        buffer.onPartial("the man who lives next")

        assertEquals(
            FinalDelta.Continue("next door."),
            buffer.onFinal("The man who lives, next door."),
        )
    }

    @Test
    fun `final resets state for the next sentence`() {
        val buffer = WaitKBuffer(WaitKPolicy.forLanguage("en"))
        buffer.onPartial("one two three four")
        buffer.onPartial("one two three four five")
        buffer.onFinal("one two three four five.")

        assertEquals("", buffer.committed)
        assertNull(buffer.onPartial("six seven"))
    }

    @Test
    fun `tokenize keeps CJK punctuation on its character`() {
        assertEquals(listOf("食", "べ", "る。"), WaitKBuffer.tokenize("食べる。"))
        assertEquals("食べる。 OK", WaitKBuffer.joinUnits(WaitKBuffer.tokenize("食べる。 OK")))
    }
}
