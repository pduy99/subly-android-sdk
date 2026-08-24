package com.helios.subly.benchmark.judge

import org.junit.Assert.assertEquals
import org.junit.Test

class JudgeScoresTest {

    @Test
    fun `parses the structured output json`() {
        val scores = JudgeScores.parse(
            """
            {
              "transcriptionAccuracy": 92,
              "transcriptionReadability": 40,
              "translationAccuracy": 78,
              "translationReadability": 55,
              "rationale": "Transcript is close but unpunctuated."
            }
            """.trimIndent()
        )

        assertEquals(92, scores.transcriptionAccuracy)
        assertEquals(40, scores.transcriptionReadability)
        assertEquals(78, scores.translationAccuracy)
        assertEquals(55, scores.translationReadability)
        assertEquals("Transcript is close but unpunctuated.", scores.rationale)
    }
}
