package com.helios.subly.benchmark.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgePromptTest {

    private val input = JudgeInput(
        clipId = "en_synthetic_01",
        engine = "vosk",
        sourceLang = "en",
        targetLang = "vi",
        referenceTranscript = "Good evening, and welcome.",
        generatedTranscript = "good evening and welcome",
        referenceTranslation = "Chào buổi tối, và xin chào mừng.",
        generatedTranslation = "chào buổi tối và chào mừng",
    )

    @Test
    fun `prompt carries all four texts and the languages`() {
        val prompt = buildJudgePrompt(input)

        listOf(
            input.referenceTranscript,
            input.generatedTranscript,
            input.referenceTranslation,
            input.generatedTranslation,
            "en", "vi",
        ).forEach { assertTrue("prompt missing: $it", it in prompt) }
    }

    @Test
    fun `prompt names every score the schema demands`() {
        val prompt = buildJudgePrompt(input)
        listOf(
            "transcriptionAccuracy",
            "transcriptionReadability",
            "translationAccuracy",
            "translationReadability",
            "rationale",
        ).forEach { assertTrue("prompt missing score key: $it", it in prompt) }
    }

    @Test
    fun `prompt is deterministic`() {
        assertEquals(buildJudgePrompt(input), buildJudgePrompt(input))
    }

    @Test
    fun `prompt version is pinned`() {
        assertEquals("v2", PROMPT_VERSION)
    }

    @Test
    fun `prompt shows caption boundaries as numbered lines`() {
        val withCaps = input.copy(
            captions = listOf(
                CaptionPair("Good evening.", "Chào buổi tối."),
                CaptionPair("and welcome", "và chào mừng"),
            )
        )
        val prompt = buildJudgePrompt(withCaps)
        assertTrue("1. Good evening." in prompt)
        assertTrue("2. and welcome" in prompt)
        assertTrue("1. Chào buổi tối." in prompt)
        assertTrue("generated_captions_source" in prompt)
        assertTrue("generated_captions_translated" in prompt)
    }

    @Test
    fun `prompt says so when boundaries were not recorded`() {
        val prompt = buildJudgePrompt(input)   // no captions
        assertTrue("Not recorded" in prompt)
        assertTrue("generated_captions_source" !in prompt)
    }
}
