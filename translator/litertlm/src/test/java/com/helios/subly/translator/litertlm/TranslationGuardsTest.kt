package com.helios.subly.translator.litertlm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationGuardsTest {

    // ---- echo -------------------------------------------------------------

    @Test
    fun `a long sentence handed back verbatim is an echo`() {
        assertTrue(
            TranslationGuards.isSourceEcho(
                translated = "The quick brown fox jumps.",
                source = "The quick brown fox jumps.",
                sourceTag = "en",
                targetTag = "vi",
            )
        )
    }

    @Test
    fun `a short unchanged output is not flagged`() {
        // "2016." is the same string in every target language; failing the
        // caption over a correct translation would be worse than the echo.
        assertFalse(
            TranslationGuards.isSourceEcho("2016.", "2016.", "en", "vi")
        )
        assertFalse(
            TranslationGuards.isSourceEcho("Sarah Connor", "Sarah Connor", "en", "vi")
        )
    }

    @Test
    fun `same-language passthrough is never an echo`() {
        assertFalse(
            TranslationGuards.isSourceEcho(
                "The quick brown fox jumps.",
                "The quick brown fox jumps.",
                sourceTag = "en",
                targetTag = "EN",
            )
        )
    }

    @Test
    fun `whitespace-only text has nothing to echo`() {
        assertFalse(TranslationGuards.isSourceEcho(" ", " ", "en", "vi"))
    }

    @Test
    fun `a real translation is not an echo`() {
        assertFalse(
            TranslationGuards.isSourceEcho(
                "Con cáo nâu nhanh nhẹn nhảy qua.",
                "The quick brown fox jumps.",
                "en",
                "vi",
            )
        )
    }

    // ---- degeneracy -------------------------------------------------------

    @Test
    fun `blank output is degenerate`() {
        assertTrue(TranslationGuards.isDegenerate(""))
        assertTrue(TranslationGuards.isDegenerate("   "))
    }

    @Test
    fun `a normal sentence is not degenerate`() {
        assertFalse(TranslationGuards.isDegenerate("Con cáo nâu nhanh nhẹn nhảy qua con chó."))
    }

    @Test
    fun `a short exact repetition is caught even though its ratio is high`() {
        // 0.5 unique ratio — well above the ratio gate, so only the
        // exact-repeat check can catch this one.
        assertTrue(TranslationGuards.isDegenerate("xin chào xin chào"))
        assertTrue(TranslationGuards.isDegenerate("không không không"))
    }

    @Test
    fun `a long rambling loop is caught by the ratio`() {
        assertTrue(TranslationGuards.isDegenerate(("và tôi " + "rất ".repeat(30)).trim()))
    }

    @Test
    fun `a one or two word translation is never judged`() {
        assertFalse(TranslationGuards.isDegenerate("Vâng."))
        assertFalse(TranslationGuards.isDegenerate("Chào bạn"))
    }

    @Test
    fun `legitimate repetition inside a longer sentence survives`() {
        assertFalse(
            TranslationGuards.isDegenerate("Rất rất tốt, cảm ơn bạn nhiều lắm nhé.")
        )
    }
}
