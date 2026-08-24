package com.helios.subly.asr.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class CasingNormalizerTest {

    @Test
    fun `converts all-caps output to sentence case`() {
        assertEquals(
            "The chronicles of newgate volume two by arthur griffiths",
            CasingNormalizer.normalise("THE CHRONICLES OF NEWGATE VOLUME TWO BY ARTHUR GRIFFITHS", atSentenceStart = true),
        )
    }

    @Test
    fun `leaves mixed-case text untouched`() {
        // A future model that cases its own output must not be flattened.
        val already = "The Chronicles of Newgate, Volume two."
        assertEquals(already, CasingNormalizer.normalise(already))
        assertEquals("Hello world", CasingNormalizer.normalise("Hello world"))
    }

    @Test
    fun `restores the english pronoun I`() {
        assertEquals("I went there.", CasingNormalizer.normalise("I WENT THERE.", atSentenceStart = true))
        assertEquals("Then I'll go", CasingNormalizer.normalise("THEN I'LL GO", atSentenceStart = true))
        assertEquals("She and I went", CasingNormalizer.normalise("SHE AND I WENT", atSentenceStart = true))
    }

    @Test
    fun `does not touch an i inside a word`() {
        assertEquals("Inside the jail", CasingNormalizer.normalise("INSIDE THE JAIL", atSentenceStart = true))
        assertEquals("Prisoners in it", CasingNormalizer.normalise("PRISONERS IN IT", atSentenceStart = true))
    }

    @Test
    fun `handles text with no letters`() {
        assertEquals("1234", CasingNormalizer.normalise("1234"))
        assertEquals("", CasingNormalizer.normalise(""))
        assertEquals("...", CasingNormalizer.normalise("..."))
    }

    @Test
    fun `capitalises past leading punctuation but not past a digit`() {
        assertEquals("\"Quoted opening", CasingNormalizer.normalise("\"QUOTED OPENING", atSentenceStart = true))
        assertEquals("1984 was the year", CasingNormalizer.normalise("1984 WAS THE YEAR", atSentenceStart = true))
    }

    @Test
    fun `is idempotent`() {
        val once = CasingNormalizer.normalise("HUNDREDS OF WOMEN AND CHILDREN", atSentenceStart = true)
        assertEquals(once, CasingNormalizer.normalise(once))
    }

    @Test
    fun `preserves word content exactly`() {
        val src = "NO REMONSTRANCE WAS ATTENDED TO NO STEPS TAKEN"
        val out = CasingNormalizer.normalise(src)
        assertEquals(src.lowercase(), out.lowercase())
    }

    @Test
    fun `does not capitalise a caption that opens mid-clause`() {
        // The benchmark caught this as a real regression: capitalising every
        // caption produced "Capacity", "Day", "Came" mid-sentence, which the
        // judge scored as *worse* than the original all-caps.
        assertEquals(
            "capacity of the wards",
            CasingNormalizer.normalise("CAPACITY OF THE WARDS"),
        )
        assertEquals("constant overcrowding", CasingNormalizer.normalise("CONSTANT OVERCROWDING"))
    }

    @Test
    fun `still lower-cases and restores I when not at a sentence start`() {
        assertEquals("and I went there", CasingNormalizer.normalise("AND I WENT THERE"))
    }
}
