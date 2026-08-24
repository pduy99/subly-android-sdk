package com.helios.subly.benchmark.judge

/** Lowest-scoring entries called out at the top of the report. */
private const val WORST_CALLOUTS = 3

private val JudgedEntry.averageScore: Double
    get() = (scores.transcriptionAccuracy + scores.transcriptionReadability +
        scores.translationAccuracy + scores.translationReadability) / 4.0

/** Human-readable side-by-side report for one benchmark run. */
fun renderReport(
    meta: RunMetadata,
    judged: List<JudgedEntry>,
    skipped: List<String>,
): String = buildString {
    appendLine("# Subly SDK accuracy benchmark — ${meta.runId}")
    appendLine()
    appendLine("| | |")
    appendLine("|---|---|")
    appendLine("| Device | ${meta.deviceModel} (API ${meta.deviceSdk}) |")
    appendLine("| SDK commit | `${meta.sdkGitSha}` |")
    appendLine("| Judge model | ${meta.judgeModel} |")
    appendLine("| Prompt version | ${meta.promptVersion} |")
    if (judged.isNotEmpty()) {
        appendLine()
        appendLine("## Summary — language × engine (averages, 0–100)")
        appendLine()
        appendLine("| Language | Engine | Entries | Transcription acc. | Transcription read. | Translation acc. | Translation read. |")
        appendLine("|---|---|---|---|---|---|---|")
        judged.groupBy { it.input.sourceLang to it.input.engine }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, group) ->
                val (lang, engine) = key
                fun avg(pick: (JudgeScores) -> Int) =
                    Math.round(group.map { pick(it.scores) }.average())
                appendLine(
                    "| $lang | $engine | ${group.size} " +
                        "| ${avg { it.transcriptionAccuracy }} | ${avg { it.transcriptionReadability }} " +
                        "| ${avg { it.translationAccuracy }} | ${avg { it.translationReadability }} |"
                )
            }

        appendLine()
        appendLine("## Worst entries")
        appendLine()
        judged.sortedBy { it.averageScore }
            .take(WORST_CALLOUTS)
            .forEach { entry ->
                val (input, s) = entry
                appendLine(
                    "- **${input.clipId} × ${input.engine}** (${input.sourceLang}→${input.targetLang}) " +
                        "— avg ${Math.round(entry.averageScore)}: " +
                        "${s.transcriptionAccuracy}/${s.transcriptionReadability}/" +
                        "${s.translationAccuracy}/${s.translationReadability}"
                )
            }
    }

    appendLine()
    appendLine("## Scores (0–100)")
    appendLine()
    appendLine("| Clip | Engine | Lang | Transcription acc. | Transcription read. | Translation acc. | Translation read. |")
    appendLine("|---|---|---|---|---|---|---|")
    judged.forEach { (input, s) ->
        appendLine(
            "| ${input.clipId} | ${input.engine} | ${input.sourceLang}→${input.targetLang} " +
                "| ${s.transcriptionAccuracy} | ${s.transcriptionReadability} " +
                "| ${s.translationAccuracy} | ${s.translationReadability} |"
        )
    }
    appendLine()
    appendLine("## Judge rationale")
    judged.forEach { (input, s) ->
        appendLine()
        appendLine("### ${input.clipId} × ${input.engine} (${input.sourceLang}→${input.targetLang})")
        appendLine()
        appendLine(s.rationale.trim())
    }
    if (skipped.isNotEmpty()) {
        appendLine()
        appendLine("## Not judged")
        appendLine()
        skipped.forEach { appendLine("- $it") }
    }
}

/** Machine-diffable raw scores for comparing runs. */
fun renderScoresJson(meta: RunMetadata, judged: List<JudgedEntry>): String {
    val payload = mapOf(
        "runId" to meta.runId,
        "device" to mapOf("model" to meta.deviceModel, "sdk" to meta.deviceSdk),
        "sdkGitSha" to meta.sdkGitSha,
        "judgeModel" to meta.judgeModel,
        "promptVersion" to meta.promptVersion,
        "entries" to judged.map { (input, s) ->
            mapOf(
                "clipId" to input.clipId,
                "engine" to input.engine,
                "sourceLang" to input.sourceLang,
                "targetLang" to input.targetLang,
                "transcriptionAccuracy" to s.transcriptionAccuracy,
                "transcriptionReadability" to s.transcriptionReadability,
                "translationAccuracy" to s.translationAccuracy,
                "translationReadability" to s.translationReadability,
                "rationale" to s.rationale,
            )
        },
    )
    return gson.toJson(payload)
}
