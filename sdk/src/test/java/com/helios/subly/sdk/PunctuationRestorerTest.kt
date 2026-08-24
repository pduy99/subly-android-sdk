package com.helios.subly.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationRestorerTest {

    private val en = PunctuationRestorer("en")
    private val ja = PunctuationRestorer("ja")
    private val zh = PunctuationRestorer("zh")

    @Test
    fun `terminates and capitalises unpunctuated english`() {
        assertEquals(
            "Good evening and welcome to the news.",
            en.restore("good evening and welcome to the news"),
        )
    }

    @Test
    fun `leaves already-punctuated text alone`() {
        // whisper punctuates; restoring over it would double the marks.
        assertEquals("Good evening.", en.restore("Good evening."))
        assertEquals("Really?", en.restore("Really?"))
        assertEquals("Stop!", en.restore("Stop!"))
        assertEquals("晚上好。", ja.restore("晚上好。"))
    }

    @Test
    fun `uses full-width terminator for unspaced scripts and does not capitalise`() {
        assertEquals("広島県竹原市。", ja.restore("広島県竹原市"))
        assertEquals("广州市房地产中介协会分析。", zh.restore("广州市房地产中介协会分析"))
    }

    @Test
    fun `restored text is a terminator SentenceExtractor recognises`() {
        // The whole point: the extractor can only split on a terminator it
        // knows, so a restorer emitting some other mark would be inert.
        listOf(en.restore("hello there"), ja.restore("こんにちは"), zh.restore("你好"))
            .forEach { assertEquals(true, it.last() in SentenceExtractor.SENTENCE_TERMINATORS) }
    }

    @Test
    fun `preserves interior casing`() {
        assertEquals("The NASA launch succeeded.", en.restore("the NASA launch succeeded"))
        assertEquals("IBM reported earnings.", en.restore("IBM reported earnings"))
    }

    @Test
    fun `handles empty and whitespace-only input`() {
        assertEquals("", en.restore(""))
        assertEquals("", en.restore("   "))
        assertEquals("", ja.restore(" \n "))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("Hello there.", en.restore("  hello there  "))
    }

    @Test
    fun `capitalises the first letter past a leading non-letter`() {
        assertEquals("\"Quoted opening.", en.restore("\"quoted opening"))
        assertEquals("2019 was the year.", en.restore("2019 was the year"))
    }

    @Test
    fun `is idempotent`() {
        val once = en.restore("good evening")
        assertEquals(once, en.restore(once))
    }
}
