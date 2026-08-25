package com.helios.subly.benchmark

import android.content.Context
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.asr.sherpa.SherpaOnnxTranscriber
import com.helios.subly.asr.vosk.VoskTranscriber
import com.helios.subly.asr.whisper.WhisperTranscriber

/**
 * The engine axis of the benchmark matrix.
 *
 * [languages] mirrors each transcriber's own `supportedLanguages()`,
 * restricted to the benchmark languages (en/zh/ja) — declared statically so
 * the matrix is known without instantiating engines. Pairs outside an
 * engine's set are recorded as skipped, never run and never failed.
 *
 * [quiescenceMs] is engine-specific: streaming engines (Vosk, Sherpa)
 * finalize within their ~20 s endpoint cadence, while Whisper decodes
 * chunks after playback ends and can go much longer between finals on
 * older hardware without being done.
 */
data class BenchmarkEngine(
    val name: String,
    val languages: Set<String>,
    val quiescenceMs: Long,
    /**
     * Whether the session restores punctuation. Matches the SDK default, so
     * every entry in the catalogue measures the shipping configuration.
     *
     * Set it to `false` on a duplicate entry to re-open the A/B — that pairs
     * both settings on the same audio within one run, instead of comparing
     * two runs an hour apart where judge noise and device state also differ.
     * The paired `-nopunct` rows that first measured this were removed once
     * the feature became the default.
     */
    val punctuation: Boolean = true,
    val factory: (Context) -> SublyAsr,
)

object EngineCatalog {
    fun engines(): List<BenchmarkEngine> = listOf(
        BenchmarkEngine("vosk", setOf("en", "zh", "ja"), quiescenceMs = 20_000) {
            VoskTranscriber(it)
        },
        // Deliberately the SDK default (small-q5_1) with no override, so the
        // baseline measures what ships. Quiescence is 90 s because that model
        // runs at roughly 3.2x real time — a 60 s clip is still being
        // transcribed long after playback ends.
        BenchmarkEngine("whisper", setOf("en", "zh", "ja"), quiescenceMs = 90_000) {
            // Model capacity was measured, not assumed: large-v3-turbo-q5_0
            // lifted CJK character agreement from 63%/58% to 80%/82% — and
            // ran at RTF 12 on this device, 61 s of inference for a 5 s
            // window. Accurate and unusable. The gap is real but it is not
            // reachable in real time on 2019-class silicon, so the default
            // stays at small-q5_1.
            WhisperTranscriber(it)
        },
        BenchmarkEngine("sherpa", setOf("en"), quiescenceMs = 20_000) {
            SherpaOnnxTranscriber(it)
        },
    )
}
