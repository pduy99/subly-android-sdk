package com.helios.subly.benchmark.latency

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.google.gson.GsonBuilder
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.asr.vosk.VoskTranscriber
import com.helios.subly.translator.litertlm.LiteRtLmModel
import com.helios.subly.translator.mlkit.MlKitTranslator
import com.helios.subly.asr.sherpa.SenseVoiceTranscriber
import com.helios.subly.asr.sherpa.SherpaAutoTranscriber
import com.helios.subly.asr.sherpa.SherpaOnnxTranscriber
import com.helios.subly.asr.whisper.WhisperTranscriber
import com.helios.subly.benchmark.FileAudioCapture
import com.helios.subly.benchmark.dataset.DatasetManifest
import com.helios.subly.benchmark.results.ClipLatency
import com.helios.subly.benchmark.results.DelegateAttemptRecord
import com.helios.subly.benchmark.results.DelegateRecord
import com.helios.subly.benchmark.results.LatencyDistribution
import com.helios.subly.benchmark.results.LatencyRun
import com.helios.subly.benchmark.results.LatencyTargets
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.EngineState
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.sdk.Subly
import com.helios.subly.sdk.streaming.StageMetric
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import com.helios.subly.translator.litertlm.LiteRtLmTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-time latency of the streaming translation pipeline on real speech.
 *
 * Plays benchmark clips at real time through a full session (file audio →
 * ASR → wait-k → incremental LLM translation) and measures, per sentence:
 *
 * | metric | from | to |
 * |---|---|---|
 * | `ttft_ms` | a clause committed (or the final arrived) | first translated token |
 * | `piece_ms` | a clause committed | its translation complete |
 * | `final_latency_ms` | ASR final | translated sentence published |
 * | `speech_end_to_translation_ms` | last voiced audio frame before the ASR final | translated sentence published |
 * | `speech_onset_to_first_translation_ms` | speech resumes after the previous sentence | first translated text of the sentence |
 *
 * `speech_end_to_translation_ms` is the end-to-end audio-to-text number: it
 * includes ASR endpointing, the wait-k hold and translation. Peak memory and
 * the compute delegate each engine landed on are recorded too.
 *
 * Writes `latency.json` (structured, with p50/p90/p99) to
 * `additional_test_output`, which Gradle pulls into
 * `benchmark/build/outputs/connected_android_test_additional_output/`.
 * `benchmark/tools/latency_report.py` prints it. Targets are reported in
 * the JSON, not asserted: the test fails only on harness errors, the same
 * rule as the accuracy benchmark.
 *
 * ```
 * ./gradlew :benchmark:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.helios.subly.benchmark.latency.StreamingLatencyBenchmarkTest
 * ```
 * Optional runner arguments: `latencyAsr` (`vosk`, default, or `whisper`),
 * `latencyClips` (comma-separated clip ids), `latencyWhisperGpu`
 * (`false` keeps whisper on the CPU), `latencyTarget` (overrides the
 * per-clip target language) and `latencyMode=baseline`, which hides
 * the translator's incremental interface so the session translates finals
 * inline, one call per sentence: the pipeline as it was before streaming
 * translation, measured on the same device, models and clips.
 */
class StreamingLatencyBenchmarkTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun measureStreamingLatency() = runBlocking {
        val asrName = args.getString("latencyAsr") ?: "vosk"
        val manifest = DatasetManifest.parse(
            instrumentation.context.assets.open("dataset/manifest.json").bufferedReader().readText()
        )
        val wanted = args.getString("latencyClips")?.split(',')?.map { it.trim() }?.toSet()
            ?: DEFAULT_CLIPS
        // Real-world clips live outside the FLEURS manifest, in realworld/.
        val realWorld = (instrumentation.context.assets.list("realworld") ?: emptyArray())
            .filter { it.endsWith(".mp4") }
            .map { name ->
                val id = name.removeSuffix(".mp4")
                DatasetManifest.Clip(
                    id = id,
                    language = id.substringBefore('_'),
                    audio = "../realworld/$name",
                    durationSec = REALWORLD_DURATION_SEC[id] ?: 60,
                )
            }
        val clips = (manifest.clips + realWorld).filter { it.id in wanted }
        assertTrue("no clips matched $wanted", clips.isNotEmpty())

        val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val memory = MemorySampler().also { it.start(memoryScope) }
        val delegates = LinkedHashMap<String, AccelerationReport>()
        val results = clips.map { clip -> runClip(clip, asrName, delegates) }
        val memoryRecord = memory.stop()
        memoryScope.cancel()

        val targets = LatencyTargets(ttftMs = TARGET_TTFT_MS, speechEndToTranslationMs = TARGET_E2E_MS)
        val summary = linkedMapOf(
            "ttft_ms" to LatencyDistribution.of(results.flatMap { it.ttftMs }),
            "piece_ms" to LatencyDistribution.of(results.flatMap { it.pieceMs }),
            "final_latency_ms" to LatencyDistribution.of(results.flatMap { it.finalLatencyMs }),
            "speech_end_to_translation_ms" to
                LatencyDistribution.of(results.flatMap { it.speechEndToTranslationMs }),
            "speech_end_to_first_token_ms" to
                LatencyDistribution.of(results.flatMap { it.speechEndToFirstTokenMs }),
            "speech_onset_to_first_translation_ms" to
                LatencyDistribution.of(results.flatMap { it.speechOnsetToFirstTranslationMs }),
        )
        val run = LatencyRun(
            runId = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()),
            device = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            gitSha = args.getString("benchmarkGitSha") ?: "unknown",
            asrEngine = when {
                args.getString("latencyMode") == "baseline" -> "$asrName, baseline (inline finals)"
                args.getString("latencyDrafts") == "false" -> "$asrName, streaming, no drafts"
                else -> "$asrName, streaming, drafts"
            },
            delegates = delegates.values.map { it.toRecord() },
            targets = targets,
            summary = summary,
            targetsMet = mapOf(
                "ttft_p90" to summary["ttft_ms"]?.let { it.p90 <= targets.ttftMs },
                "speech_end_to_translation_p90" to
                    summary["speech_end_to_translation_ms"]?.let { it.p90 <= targets.speechEndToTranslationMs },
                "speech_end_to_first_token_p90" to
                    summary["speech_end_to_first_token_ms"]?.let { it.p90 <= targets.speechEndToTranslationMs },
            ),
            memory = memoryRecord,
            clips = results,
        )

        val json = GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(run)
        TestStorage().openOutputFile("latency.json").bufferedWriter().use { it.write(json) }
        // Also to logcat, one line, so a run without TestStorage still has it.
        Log.i(TAG, "LATENCY_JSON " + GsonBuilder().serializeNulls().create().toJson(run))
        summary.forEach { (name, d) ->
            Log.i(TAG, "$name n=${d?.count} p50=${d?.p50} p90=${d?.p90} p99=${d?.p99} max=${d?.max}")
        }

        val harnessErrors = results.mapNotNull { r -> r.error?.let { "${r.clipId}: $it" } }
        assertTrue("harness errors (latency.json still written):\n" + harnessErrors.joinToString("\n"), harnessErrors.isEmpty())
    }

    private suspend fun runClip(
        clip: DatasetManifest.Clip,
        asrName: String,
        delegates: MutableMap<String, AccelerationReport>,
    ): ClipLatency {
        val source = requireNotNull(clip.language)
        // SOV and Chinese sources go to English; English goes to Vietnamese.
        val target = args.getString("latencyTarget") ?: if (source == "en") "vi" else "en"
        val audio = copyAssetToCache(requireNotNull(clip.audio))
        val tracker = VoicedFrameTracker()
        val capture = FileAudioCapture(audio)

        fun failed(reason: String) = ClipLatency(
            clipId = requireNotNull(clip.id), language = source, target = target,
            durationSec = clip.durationSec, finals = 0, forcedCommits = 0,
            ttftMs = emptyList(), pieceMs = emptyList(), finalLatencyMs = emptyList(),
            speechEndToTranslationMs = emptyList(), speechEndToFirstTokenMs = emptyList(),
            speechOnsetToFirstTranslationMs = emptyList(),
            error = reason,
        )

        val baseline = args.getString("latencyMode") == "baseline"
        val timedCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
        val subly = Subly.Builder()
            .setAsrEngine { asrFactory(asrName) }
            .setTranslator {
                when (val choice = args.getString("latencyTranslator")) {
                    // Per-pair NMT: translated inline, finals and partials.
                    "mlkit" -> MlKitTranslator()
                    else -> {
                        val translator = if (choice == null) LiteRtLmTranslator(context)
                        else LiteRtLmTranslator(context, model = LiteRtLmModel.valueOf(choice))
                        if (baseline) FinalsOnlyTimer(translator, timedCalls) else translator
                    }
                }
            }
            .setAudioCapture { TrackedCapture(capture, tracker) }
            .setTranslationDrafts(args.getString("latencyDrafts") != "false")
            .build()

        return try {
            subly.createSession(LanguageConfig(source = source, target = target)).use { session ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val metrics = CopyOnWriteArrayList<StageMetric>()
                val finalsAt = CopyOnWriteArrayList<Long>()
                // Per sentence: wall time of its first non-empty translation.
                val firstTranslationAt = CopyOnWriteArrayList<Long>()
                var sentenceHasTranslation = false
                var lastActivityAt = SystemClock.elapsedRealtime()

                scope.launch { session.stageMetrics.collect { metrics += it } }
                scope.launch {
                    session.captions.collect { caption ->
                        val now = System.nanoTime()
                        lastActivityAt = SystemClock.elapsedRealtime()
                        // A finals-only session fills a partial's translation
                        // with its source text; that is not a translation.
                        val translated = caption.translatedText.isNotBlank() &&
                            (caption.isFinal || caption.translatedText != caption.originalText)
                        if (!sentenceHasTranslation && translated) {
                            firstTranslationAt += now
                            sentenceHasTranslation = true
                        }
                        if (caption.isFinal) {
                            finalsAt += now
                            sentenceHasTranslation = false
                        }
                    }
                }

                try {
                    session.prepare()
                    val prepared = withTimeout(PREPARE_TIMEOUT_MS) {
                        session.state.first { it is EngineState.Ready || it is EngineState.Error }
                    }
                    if (prepared is EngineState.Error) return failed("prepare failed: ${prepared.error}")
                    session.accelerationReports().forEach { delegates[it.component] = it }

                    session.start()
                    // Quiet is measured from playback, not from before the
                    // (tens of seconds of) preparation above.
                    lastActivityAt = SystemClock.elapsedRealtime()
                    val deadline = SystemClock.elapsedRealtime() +
                        clip.durationSec * 1_000L + FileAudioCapture.TRAILING_SILENCE_MS + PIPELINE_SLACK_MS
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val state = session.state.value
                        if (state is EngineState.Error) return failed("pipeline failed: ${state.error}")
                        val quiet = SystemClock.elapsedRealtime() - lastActivityAt >= quiescenceMs(asrName)
                        if (capture.finished.isCompleted && quiet) break
                        delay(200)
                    }
                    session.stop()
                } finally {
                    scope.cancel()
                }

                // Zero captions from a clip of read speech means the pipeline
                // is broken (it is how a miscomputing GPU path first showed
                // up), not that it was fast: fail rather than report nothing.
                if (finalsAt.isEmpty()) return failed("no captions produced")

                // Baseline: no stage metrics. Each final was translated inline
                // in one call that started at its ASR final, so the timed
                // calls give the same quantities. The warm-up call during
                // prepare is not a caption and is dropped.
                val captionCalls = if (baseline) timedCalls.drop(1) else emptyList()
                val finalLatencies = if (baseline) {
                    captionCalls.map { (start, end) -> (end - start) / 1_000_000 }
                } else {
                    metrics.filter { it.kind == StageMetric.Kind.FINAL }.map { it.ms }
                }
                // Pair each published final with its ASR-final time (published
                // time minus the stage's final latency), then look back to the
                // last voiced frame before it.
                val speechEnd = finalsAt.zip(finalLatencies).mapNotNull { (publishedAt, latencyMs) ->
                    val asrFinalAt = publishedAt - latencyMs * 1_000_000
                    tracker.lastVoicedAtOrBefore(asrFinalAt)?.let { (publishedAt - it) / 1_000_000 }
                }
                // First token of each final, against the same speech end.
                // Baseline shows nothing until the whole translation is done.
                val finalTtfts = if (baseline) finalLatencies else
                    metrics.filter { it.kind == StageMetric.Kind.FINAL_TTFT }.map { it.ms }
                val speechEndToFirstToken = finalsAt.zip(finalLatencies).zip(finalTtfts)
                    .mapNotNull { (pair, firstTokenMs) ->
                        val (publishedAt, latencyMs) = pair
                        val asrFinalAt = publishedAt - latencyMs * 1_000_000
                        tracker.lastVoicedAtOrBefore(asrFinalAt)?.let {
                            (asrFinalAt + firstTokenMs * 1_000_000 - it) / 1_000_000
                        }
                    }
                val onsets = buildList {
                    var previousFinal = 0L
                    for ((i, first) in firstTranslationAt.withIndex()) {
                        val onset = tracker.firstVoicedAfter(previousFinal)
                        if (onset != null && first >= onset) add((first - onset) / 1_000_000)
                        previousFinal = finalsAt.getOrNull(i) ?: break
                    }
                }

                ClipLatency(
                    clipId = requireNotNull(clip.id),
                    language = source,
                    target = target,
                    durationSec = clip.durationSec,
                    finals = finalsAt.size,
                    forcedCommits = metrics.count { it.kind == StageMetric.Kind.FORCED_COMMIT },
                    // Baseline shows nothing until the whole translation is
                    // done, so its first token is its last.
                    ttftMs = if (baseline) finalLatencies else metrics.filter { it.kind == StageMetric.Kind.TTFT }.map { it.ms },
                    pieceMs = metrics.filter { it.kind == StageMetric.Kind.PIECE }.map { it.ms },
                    finalLatencyMs = finalLatencies,
                    speechEndToTranslationMs = speechEnd,
                    speechEndToFirstTokenMs = speechEndToFirstToken,
                    speechOnsetToFirstTranslationMs = onsets,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "clip ${clip.id} failed", e)
            failed("harness exception: $e")
        } finally {
            audio.delete()
        }
    }

    private fun asrFactory(name: String): SublyAsr = when (name) {
        // latencyWhisperGpu=false pins whisper to the CPU, for A/B runs.
        "whisper" -> WhisperTranscriber(context, preferGpu = args.getString("latencyWhisperGpu") != "false")
        "vosk" -> VoskTranscriber(context)
        "sherpa" -> SherpaOnnxTranscriber(context)
        "sensevoice" -> SenseVoiceTranscriber(context)
        "auto" -> SherpaAutoTranscriber(context)
        else -> error("unknown latencyAsr '$name' (vosk|whisper|sherpa|sensevoice|auto)")
    }

    private fun copyAssetToCache(assetPath: String): File {
        val out = File.createTempFile("latency", ".mp4", context.cacheDir)
        // Manifest paths are relative to dataset/; real-world ones step out.
        val path = if (assetPath.startsWith("../")) assetPath.removePrefix("../") else "dataset/$assetPath"
        out.outputStream().use { dst ->
            instrumentation.context.assets.open(path).use { it.copyTo(dst) }
        }
        return out
    }

    private fun AccelerationReport.toRecord() = DelegateRecord(
        component = component,
        active = active.name,
        hardwareAccelerated = isHardwareAccelerated,
        fallback = isFallback,
        attempts = attempts.map { DelegateAttemptRecord(it.delegate.name, it.succeeded, it.initMs, it.failure) },
    )

    private companion object {
        const val TAG = "StreamingLatency"

        /** Two clips per language: SVO→SVO (en), SVO (zh), and SOV (ja). */
        val DEFAULT_CLIPS = setOf(
            "fleurs_en_01", "fleurs_en_02",
            "fleurs_ja_01", "fleurs_ja_02",
            "fleurs_zh_01", "fleurs_zh_02",
        )

        /** Lengths of the real-world clips, which have no manifest entry. */
        val REALWORLD_DURATION_SEC = mapOf("zh_dialogue_01" to 49)

        /** The task specification's targets, recorded against the measurements. */
        const val TARGET_TTFT_MS = 300L
        const val TARGET_E2E_MS = 800L

        /** First run downloads the 1.6 GB checkpoint. */
        const val PREPARE_TIMEOUT_MS = 45 * 60 * 1_000L

        /** Time for the last sentence to finish translating after playback. */
        const val PIPELINE_SLACK_MS = 90_000L

        /**
         * No caption for this long after playback means the pipeline is done.
         * Whisper decodes behind real time on harder languages and can go
         * much longer between finals, as in the accuracy benchmark.
         */
        fun quiescenceMs(asr: String): Long = if (asr == "whisper") 90_000L else 12_000L
    }
}

/**
 * Presents a translator as a plain [SublyTranslator] (no incremental
 * interface, finals only) and records each translate call's wall-clock span.
 */
private class FinalsOnlyTimer(
    private val inner: LiteRtLmTranslator,
    private val calls: MutableList<Pair<Long, Long>>,
) : SublyTranslator {
    override val translatesPartials = false

    override suspend fun translate(text: String): String {
        val start = System.nanoTime()
        return try {
            inner.translate(text)
        } finally {
            calls += start to System.nanoTime()
        }
    }

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = inner.prepareModel(config)
    override fun release() = inner.release()
    override fun supportedLanguages(): List<String> = inner.supportedLanguages()
}
