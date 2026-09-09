package com.helios.subly.translator.litertlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptTest {

    @Test
    fun `language names come from the platform tables, not a hand-written map`() {
        assertEquals("Vietnamese", TranslationPrompt.languageName("vi"))
        assertEquals("Chinese", TranslationPrompt.languageName("zh"))
        assertEquals("Japanese", TranslationPrompt.languageName("ja"))
        assertEquals("English", TranslationPrompt.languageName("en-US"))
    }

    @Test
    fun `an unknown tag falls back to itself rather than to nothing`() {
        assertEquals("zzz", TranslationPrompt.languageName("zzz"))
    }

    @Test
    fun `the system instruction names both languages`() {
        val instruction = TranslationPrompt.systemInstruction("en", "vi")

        assertTrue(instruction.contains("English"))
        assertTrue(instruction.contains("Vietnamese"))
    }

    @Test
    fun `plain output is passed through untouched`() {
        assertEquals("Xin chào thế giới.", TranslationPrompt.clean("Xin chào thế giới."))
    }

    @Test
    fun `a label prefix is stripped`() {
        assertEquals("Xin chào.", TranslationPrompt.clean("Translation: Xin chào."))
        assertEquals("Xin chào.", TranslationPrompt.clean("Output:\nXin chào."))
    }

    @Test
    fun `matching wrapping quotes are stripped, one layer only`() {
        assertEquals("Xin chào.", TranslationPrompt.clean("\"Xin chào.\""))
        assertEquals("Xin chào.", TranslationPrompt.clean("“Xin chào.”"))
        assertEquals("「こんにちは」", TranslationPrompt.clean("\"「こんにちは」\""))
    }

    @Test
    fun `an unmatched quote is left alone`() {
        // A translation that legitimately opens a quotation must survive.
        assertEquals("\"Xin chào, anh ấy nói.", TranslationPrompt.clean("\"Xin chào, anh ấy nói."))
    }

    @Test
    fun `a trailing explanation is dropped`() {
        val raw = "Xin chào thế giới.\n\nNote: this is an informal greeting."

        assertEquals("Xin chào thế giới.", TranslationPrompt.clean(raw))
    }

    @Test
    fun `a markdown fence is unwrapped`() {
        assertEquals("Xin chào.", TranslationPrompt.clean("```\nXin chào.\n```"))
        assertEquals("Xin chào.", TranslationPrompt.clean("```text\nXin chào.\n```"))
    }

    @Test
    fun `a single newline inside the translation is kept`() {
        assertEquals("Dòng một\nDòng hai", TranslationPrompt.clean("Dòng một\nDòng hai"))
    }
}
