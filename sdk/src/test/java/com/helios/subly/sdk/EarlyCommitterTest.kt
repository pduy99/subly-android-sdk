package com.helios.subly.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EarlyCommitterTest {

    private val zh = EarlyCommitter(Locale.CHINESE)
    private val en = EarlyCommitter(Locale.ENGLISH)

    @Test
    fun `a finished sentence is committed once it is stable and followed by speech`() {
        // Three characters after the full stop are not yet enough speech.
        assertEquals(emptyList<String>(), zh.onPartial("硬通货随便吃都有真便宜啊。呃，你"))
        // Complete: it must stay so for three partials.
        assertEquals(emptyList<String>(), zh.onPartial("硬通货随便吃都有真便宜啊。呃，你是这"))
        assertEquals(emptyList<String>(), zh.onPartial("硬通货随便吃都有真便宜啊。呃，你是这个"))
        assertEquals(listOf("硬通货随便吃都有真便宜啊。"), zh.onPartial("硬通货随便吃都有真便宜啊。呃，你是这个卖"))
        // Not again.
        assertEquals(emptyList<String>(), zh.onPartial("硬通货随便吃都有真便宜啊。呃，你是这个卖家"))
        assertEquals("呃，你是这个卖家", zh.uncommitted("硬通货随便吃都有真便宜啊。呃，你是这个卖家"))
    }

    @Test
    fun `a sentence with nothing after it waits`() {
        assertTrue(en.onPartial("He did not set a figure.").isEmpty())
        assertTrue(en.onPartial("He did not set a figure.").isEmpty())
    }

    @Test
    fun `a full stop that moved is not committed`() {
        assertTrue(zh.onPartial("你是这个卖家。对是我走").isEmpty())
        assertTrue(zh.onPartial("你是这个卖家，对，是我。走吧你是").isEmpty())
        assertTrue(zh.onPartial("你是这个卖家，对，是我。走吧你是三").isEmpty())
        assertEquals(listOf("你是这个卖家，对，是我。"), zh.onPartial("你是这个卖家，对，是我。走吧你是三爷"))
    }

    @Test
    fun `the final keeps only what was not committed`() {
        en.onPartial("Alloys are basically a mixture of metals. Don't")
        en.onPartial("Alloys are basically a mixture of metals. Don't forget")
        en.onPartial("Alloys are basically a mixture of metals. Don't forget the")
        assertEquals(
            "Don't forget the periodic table.",
            en.onFinal("Alloys are basically a mixture of metals. Don't forget the periodic table."),
        )
        // Reset: the next utterance starts clean.
        assertEquals("Next one.", en.onFinal("Next one."))
    }

    @Test
    fun `a final that revised a committed sentence slightly still drops it`() {
        zh.onPartial("你是三爷的亲戚还是真便宜？等会儿你")
        zh.onPartial("你是三爷的亲戚还是真便宜？等会儿你就")
        zh.onPartial("你是三爷的亲戚还是真便宜？等会儿你就说")
        assertEquals("等会儿你就说你是我女儿。", zh.onFinal("你是三爷得亲戚还是是真便宜？等会儿你就说你是我女儿。"))
    }

    @Test
    fun `a final that rewrote everything is kept whole`() {
        en.onPartial("I scream. You")
        en.onPartial("I scream. You scream")
        en.onPartial("I scream. You scream for")
        assertEquals("Ice cream for everyone today.", en.onFinal("Ice cream for everyone today."))
    }

    @Test
    fun `decimals are not sentence ends`() {
        assertTrue(en.onPartial("It costs 3.5 dollars and").isEmpty())
        assertTrue(en.onPartial("It costs 3.5 dollars and more").isEmpty())
    }
}
