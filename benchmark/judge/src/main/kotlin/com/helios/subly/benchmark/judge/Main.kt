package com.helios.subly.benchmark.judge

import java.io.File
import java.util.Properties

/**
 * Stage 2 of the accuracy benchmark: loads the newest device-stage
 * results.json (or --results <path>), pairs each entry with its verified
 * references, has Claude score accuracy + readability, and writes
 * reports/<runId>/report.md + scores.json.
 *
 * Usage: ./gradlew :benchmark:judge:run [--args="--results <path> --model <id>"]
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val model = opts["--model"] ?: DEFAULT_MODEL

    val resultsFile = opts["--results"]?.let(::File) ?: findNewestResults()
    println("Judging: ${resultsFile.absolutePath}")
    val results = DeviceResults.parse(resultsFile.readText())

    val datasetDir = File(prop("benchmark.dataset.dir"))
    val manifest = DatasetManifest.parse(File(datasetDir, "manifest.json").readText())
    val clipsById = manifest.clips.associateBy { it.id }

    val judge = ClaudeJudge(model = model, apiKey = resolveApiKey())
    val judged = mutableListOf<JudgedEntry>()
    val skipped = mutableListOf<String>()
    var apiFailures = 0

    for (entry in results.entries) {
        val label = "${entry.clipId}×${entry.engine}→${entry.targetLang}"
        val clip = clipsById[entry.clipId]
        val translationPath = clip?.translations?.get(entry.targetLang)
        when {
            entry.skipped != null -> skipped += "$label: ${entry.skipped}"
            entry.error != null -> skipped += "$label: ${entry.error}"
            entry.transcript == null || entry.translation == null ->
                skipped += "$label: no generated output"
            clip?.transcript == null -> skipped += "$label: clip not in dataset manifest"
            translationPath == null -> skipped += "$label: no reference translation for target"
            else -> {
                val input = JudgeInput(
                    clipId = entry.clipId,
                    engine = entry.engine,
                    sourceLang = entry.sourceLang,
                    targetLang = entry.targetLang,
                    referenceTranscript = File(datasetDir, clip.transcript).readText(),
                    generatedTranscript = entry.transcript,
                    referenceTranslation = File(datasetDir, translationPath).readText(),
                    generatedTranslation = entry.translation,
                    captions = entry.captions,
                )
                print("  scoring $label… ")
                try {
                    judged += JudgedEntry(input, judge.judge(input))
                    println("done")
                } catch (e: Exception) {
                    // Judging costs money and time, so one API failure must not
                    // discard the entries already paid for. Record why this one
                    // could not be scored and keep going; the report is still
                    // written from whatever succeeded.
                    val reason = e.message?.lineSequence()?.firstOrNull()?.take(160)
                        ?: e::class.simpleName ?: "unknown error"
                    println("FAILED")
                    skipped += "$label: judge failed — $reason"
                    apiFailures++
                    if (apiFailures >= MAX_API_FAILURES) {
                        println()
                        println(
                            "Stopping after $apiFailures consecutive judge failures — " +
                                "the remaining entries would fail the same way. " +
                                "Writing the report from what was scored."
                        )
                        break
                    }
                }
            }
        }
    }

    val meta = RunMetadata(
        runId = results.runId,
        deviceModel = results.device.model,
        deviceSdk = results.device.sdk,
        sdkGitSha = results.sdkGitSha,
        translator = results.translator ?: "mlkit",
        judgeModel = model,
        promptVersion = PROMPT_VERSION,
    )

    val outDir = File(prop("benchmark.reports.dir"), results.runId).apply { mkdirs() }
    val reportFile = File(outDir, "report.md")
    reportFile.writeText(renderReport(meta, judged, skipped))
    File(outDir, "scores.json").writeText(renderScoresJson(meta, judged))

    println()
    println("Judged ${judged.size} entr${if (judged.size == 1) "y" else "ies"}, skipped ${skipped.size}.")
    println("Report:  ${reportFile.absolutePath}")
    println("Scores:  ${File(outDir, "scores.json").absolutePath}")
    if (judged.isEmpty()) {
        println()
        println("Nothing was scored. If the reason above mentions the API key or credit")
        println("balance, the device results are untouched — fix the key and rerun with")
        println("--args=\"--results ${resultsFile.absolutePath}\" to judge them without")
        println("repeating the device run.")
    }
}

private const val DEFAULT_MODEL = "claude-sonnet-5"

/**
 * Give up after this many judge failures in the same run. A dead API key, an
 * exhausted balance, or a network outage fails identically for every entry, so
 * there is nothing to gain from working through the rest.
 */
private const val MAX_API_FAILURES = 3

private fun parseArgs(args: Array<String>): Map<String, String> =
    args.toList().windowed(2, 2, partialWindows = false)
        .filter { it[0].startsWith("--") }
        .associate { it[0] to it[1] }

private fun prop(name: String): String =
    requireNotNull(System.getProperty(name)) { "$name not set — run via ./gradlew :benchmark:judge:run" }

private fun findNewestResults(): File {
    val root = File(prop("benchmark.results.root"))
    return root.walkTopDown()
        .filter { it.isFile && it.name == "results.json" }
        .maxByOrNull { it.lastModified() }
        ?: error(
            "No results.json under ${root.absolutePath} — run the device stage first: " +
                "./gradlew :benchmark:connectedDebugAndroidTest"
        )
}

/** ANTHROPIC_API_KEY / CLAUDE_KEY from the environment, else local.properties. */
private fun resolveApiKey(): String {
    System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("CLAUDE_KEY")?.takeIf { it.isNotBlank() }?.let { return it }

    val localProperties = File(prop("benchmark.local.properties"))
    if (localProperties.isFile) {
        val props = Properties().apply { localProperties.inputStream().use(::load) }
        listOf("ANTHROPIC_API_KEY", "CLAUDE_KEY").forEach { key ->
            props.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    error(
        "No API key found. Set ANTHROPIC_API_KEY (or CLAUDE_KEY) in the environment " +
            "or in ${localProperties.absolutePath}."
    )
}
