package com.helios.subly.benchmark.results

import com.google.gson.GsonBuilder

/**
 * The device stage's output — `results.json` in `additional_test_output` —
 * and the input contract of the host-side judge (`:benchmark:judge`).
 * Field names are part of that contract; rename only with the judge.
 */
data class BenchmarkResults(
    /** Timestamp + device tag; groups artifacts of one benchmark run. */
    val runId: String,
    val device: DeviceInfo,
    /** SDK repo commit the run measured (injected by Gradle). */
    val sdkGitSha: String,
    val entries: List<Entry>,
) {
    data class DeviceInfo(val model: String, val sdk: Int)

    /** One emitted caption: what the viewer sees on screen at one moment. */
    data class CaptionPair(val original: String, val translated: String)

    data class Entry(
        val clipId: String,
        val engine: String,
        val sourceLang: String,
        val targetLang: String,
        /** Final captions' source text joined in order, or null on [error]. */
        val transcript: String?,
        /** Final captions' translated text joined in order, or null on [error]. */
        val translation: String?,
        /**
         * The same text with caption boundaries preserved. Joining loses where
         * the SDK actually broke the stream, and those breaks are what a viewer
         * reads one chunk at a time — so the judge needs them to score
         * segmentation rather than inferring it from punctuation alone.
         */
        val captions: List<CaptionPair> = emptyList(),
        val captionCount: Int,
        /** Wall-clock pipeline time. Informational only — never judged. */
        val processingMs: Long,
        /** Harness failure description; null when the entry is sound. */
        val error: String?,
        /**
         * Why this pair was not run at all (e.g. the engine has no model for
         * the language). Skips are expected matrix gaps — never failures.
         */
        val skipped: String? = null,
    )

    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

        fun fromJson(json: String): BenchmarkResults =
            gson.fromJson(json, BenchmarkResults::class.java)
    }
}
