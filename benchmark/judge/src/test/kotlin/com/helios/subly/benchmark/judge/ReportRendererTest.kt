package com.helios.subly.benchmark.judge

import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRendererTest {

    private val judged = listOf(
        JudgedEntry(
            input = JudgeInput(
                clipId = "en_synthetic_01",
                engine = "vosk",
                sourceLang = "en",
                targetLang = "vi",
                referenceTranscript = "ref transcript",
                generatedTranscript = "gen transcript",
                referenceTranslation = "ref translation",
                generatedTranslation = "gen translation",
            ),
            scores = JudgeScores(
                transcriptionAccuracy = 92,
                transcriptionReadability = 40,
                translationAccuracy = 78,
                translationReadability = 55,
                rationale = "Close but unpunctuated.",
            ),
        )
    )

    private val meta = RunMetadata(
        runId = "2026-08-19T16-38-48_SM-G973F",
        deviceModel = "SM-G973F",
        deviceSdk = 31,
        sdkGitSha = "08d912e",
        translator = "mlkit",
        judgeModel = "claude-sonnet-5",
        promptVersion = PROMPT_VERSION,
    )

    private fun entry(
        clipId: String,
        engine: String,
        lang: String,
        scores: JudgeScores,
    ) = JudgedEntry(
        input = JudgeInput(
            clipId = clipId, engine = engine, sourceLang = lang, targetLang = "vi",
            referenceTranscript = "r", generatedTranscript = "g",
            referenceTranslation = "r", generatedTranslation = "g",
        ),
        scores = scores,
    )

    @Test
    fun `summary averages scores per language and engine`() {
        val report = renderReport(
            meta,
            listOf(
                entry("en_a", "vosk", "en", JudgeScores(80, 40, 60, 40, "x")),
                entry("en_b", "vosk", "en", JudgeScores(90, 60, 80, 60, "y")),
                entry("ja_a", "whisper", "ja", JudgeScores(70, 70, 70, 70, "z")),
            ),
            skipped = emptyList(),
        )

        // en × vosk averages: 85 / 50 / 70 / 50
        assertTrue("missing averaged summary row", "| en | vosk | 2 | 85 | 50 | 70 | 50 |" in report)
        assertTrue("missing ja summary row", "| ja | whisper | 1 | 70 | 70 | 70 | 70 |" in report)
    }

    @Test
    fun `worst entries are called out`() {
        val report = renderReport(
            meta,
            listOf(
                entry("good_clip", "vosk", "en", JudgeScores(95, 90, 92, 91, "fine")),
                entry("bad_clip", "whisper", "ja", JudgeScores(30, 20, 25, 15, "rough")),
            ),
            skipped = emptyList(),
        )

        val worstSection = report.substringAfter("## Worst entries")
        assertTrue("worst section missing bad_clip", "bad_clip" in worstSection)
        assertTrue(
            "worst section should list bad_clip before good_clip",
            worstSection.indexOf("bad_clip") < worstSection.indexOf("good_clip")
                || "good_clip" !in worstSection,
        )
    }

    @Test
    fun `report carries metadata, scores, and rationale`() {
        val report = renderReport(meta, judged, skipped = emptyList())

        listOf(
            meta.runId, meta.sdkGitSha, meta.judgeModel, PROMPT_VERSION,
            "en_synthetic_01", "vosk",
            "92", "40", "78", "55",
            "Close but unpunctuated.",
        ).forEach { assertTrue("report missing: $it", it in report) }
    }

    @Test
    fun `report lists entries the harness could not judge`() {
        val report = renderReport(
            meta, judged,
            skipped = listOf("zh_clip_02×whisper: pipeline produced no final captions"),
        )
        assertTrue("zh_clip_02" in report)
    }

    @Test
    fun `scores json is machine-diffable`() {
        val json = renderScoresJson(meta, judged)
        listOf(
            "\"runId\"", "\"promptVersion\"", "\"judgeModel\"",
            "\"transcriptionAccuracy\": 92", "\"clipId\": \"en_synthetic_01\"",
        ).forEach { assertTrue("scores.json missing: $it", it in json) }
    }
}
