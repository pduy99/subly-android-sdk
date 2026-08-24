package com.helios.subly.benchmark.judge

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Everything the judge needs to score one clip × engine × target. */
data class JudgeInput(
    val clipId: String,
    val engine: String,
    val sourceLang: String,
    val targetLang: String,
    val referenceTranscript: String,
    val generatedTranscript: String,
    val referenceTranslation: String,
    val generatedTranslation: String,
    /**
     * The generated text with caption boundaries intact — what the viewer
     * actually reads, one chunk at a time. Empty for runs recorded before
     * boundaries were captured, in which case the prompt falls back to the
     * joined text and says so.
     */
    val captions: List<CaptionPair> = emptyList(),
)

data class CaptionPair(val original: String, val translated: String)

/** The judge's verdict — all scores 0–100. */
data class JudgeScores(
    val transcriptionAccuracy: Int,
    val transcriptionReadability: Int,
    val translationAccuracy: Int,
    val translationReadability: Int,
    val rationale: String,
) {
    companion object {
        fun parse(json: String): JudgeScores = gson.fromJson(json, JudgeScores::class.java)
    }
}

data class JudgedEntry(val input: JudgeInput, val scores: JudgeScores)

data class RunMetadata(
    val runId: String,
    val deviceModel: String,
    val deviceSdk: Int,
    val sdkGitSha: String,
    val judgeModel: String,
    val promptVersion: String,
)

// -----------------------------------------------------------------------------
// Device-stage results.json contract (mirrors benchmark/src/main
// BenchmarkResults — the JSON is the interface between the two stages).
// -----------------------------------------------------------------------------

data class DeviceResults(
    val runId: String,
    val device: Device,
    val sdkGitSha: String,
    val entries: List<Entry>,
) {
    data class Device(val model: String, val sdk: Int)

    data class Entry(
        val clipId: String,
        val engine: String,
        val sourceLang: String,
        val targetLang: String,
        val transcript: String?,
        val translation: String?,
        val captions: List<CaptionPair> = emptyList(),
        val captionCount: Int,
        val processingMs: Long,
        val error: String?,
        val skipped: String? = null,
    )

    companion object {
        fun parse(json: String): DeviceResults = gson.fromJson(json, DeviceResults::class.java)
    }
}

/** The slice of dataset manifest.json the judge needs. */
data class DatasetManifest(val clips: List<Clip> = emptyList()) {
    data class Clip(
        val id: String? = null,
        val transcript: String? = null,
        val translations: Map<String, String> = emptyMap(),
    )

    companion object {
        fun parse(json: String): DatasetManifest =
            gson.fromJson(json, DatasetManifest::class.java)
    }
}

internal val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
