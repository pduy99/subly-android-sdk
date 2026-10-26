package com.helios.subly.benchmark.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `results.json` is the contract between the device stage and the host-side
 * judge — field names are load-bearing.
 */
class BenchmarkResultsTest {

    private val results = BenchmarkResults(
        runId = "2026-08-19T10-32-11_SM-G973F",
        device = BenchmarkResults.DeviceInfo(model = "SM-G973F", sdk = 31),
        sdkGitSha = "d0f3823",
        entries = listOf(
            BenchmarkResults.Entry(
                clipId = "en_synthetic_01",
                engine = "vosk",
                sourceLang = "en",
                targetLang = "vi",
                transcript = "Good evening.",
                translation = "Chào buổi tối.",
                captionCount = 1,
                processingMs = 70_123,
                error = null,
            )
        ),
    )

    @Test
    fun `caption boundaries survive the round trip`() {
        val withCaptions = results.copy(
            entries = listOf(
                results.entries.single().copy(
                    captions = listOf(
                        BenchmarkResults.CaptionPair("Good evening.", "Chào buổi tối."),
                        BenchmarkResults.CaptionPair("Welcome back.", "Chào mừng trở lại."),
                    )
                )
            )
        )
        val parsed = BenchmarkResults.fromJson(withCaptions.toJson())
        val caps = parsed.entries.single().captions
        assertEquals(2, caps.size)
        assertEquals("Welcome back.", caps[1].original)
        assertEquals("Chào mừng trở lại.", caps[1].translated)
        assertTrue("\"captions\"" in withCaptions.toJson())
    }

    @Test
    fun `round trips through json`() {
        assertEquals(results, BenchmarkResults.fromJson(results.toJson()))
    }

    @Test
    fun `json uses the contract field names`() {
        val json = results.toJson()
        listOf(
            "runId", "device", "sdkGitSha", "entries",
            "clipId", "engine", "sourceLang", "targetLang",
            "transcript", "translation", "captionCount", "processingMs",
        ).forEach { field ->
            assertTrue("missing field \"$field\"", "\"$field\"" in json)
        }
    }

    @Test
    fun `skipped pairs survive the round trip and are not errors`() {
        val skippedRun = results.copy(
            entries = listOf(
                results.entries.single().copy(
                    engine = "sherpa",
                    transcript = null,
                    translation = null,
                    captionCount = 0,
                    skipped = "engine does not support language 'zh'",
                )
            )
        )
        val parsed = BenchmarkResults.fromJson(skippedRun.toJson())
        val entry = parsed.entries.single()
        assertEquals("engine does not support language 'zh'", entry.skipped)
        assertEquals(null, entry.error)
    }

    @Test
    fun `errors survive the round trip`() {
        val failed = results.copy(
            entries = listOf(
                results.entries.single().copy(
                    transcript = null,
                    translation = null,
                    error = "model download failed",
                )
            )
        )
        val parsed = BenchmarkResults.fromJson(failed.toJson())
        assertEquals("model download failed", parsed.entries.single().error)
    }
}
