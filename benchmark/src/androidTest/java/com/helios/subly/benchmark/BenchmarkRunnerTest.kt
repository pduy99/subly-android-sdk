package com.helios.subly.benchmark

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.helios.subly.benchmark.dataset.DatasetManifest
import com.helios.subly.benchmark.results.BenchmarkResults
import com.helios.subly.core.model.Caption
import com.helios.subly.core.model.EngineState
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.sdk.Subly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * The device stage of the accuracy benchmark: runs every manifest clip
 * through the SDK pipeline (file capture → ASR → sentence assembly →
 * translation) and writes `results.json` to `additional_test_output` for the
 * host-side judge.
 *
 * This test fails only on *harness* errors — engine crashes, model
 * preparation failures, empty output. Quality is judged by the host stage,
 * never here.
 *
 * Covers the full clip × engine × target matrix (see [EngineCatalog]);
 * pairs an engine has no model for are recorded as skipped, never failed.
 */
class BenchmarkRunnerTest {

    @Test
    fun runBenchmark() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets

        val manifest = DatasetManifest.parse(
            assets.open("dataset/manifest.json").bufferedReader().readText()
        )
        assertTrue("no clips in the dataset manifest", manifest.clips.isNotEmpty())

        // Optional filters for a fast inner loop — a full matrix run takes over
        // an hour, which is too slow to iterate against:
        //   -Pandroid.testInstrumentationRunnerArguments.benchmarkClips=aishell_zh_01
        //   -Pandroid.testInstrumentationRunnerArguments.benchmarkEngines=whisper
        val args = InstrumentationRegistry.getArguments()
        fun filterOf(key: String): Set<String>? = args.getString(key)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?.takeIf { it.isNotEmpty() }

        val clipFilter = filterOf("benchmarkClips")
        val engineFilter = filterOf("benchmarkEngines")
        val translator = BenchmarkTranslator.of(args.getString("benchmarkTranslator"))
        val clips = manifest.clips.filter { clipFilter == null || it.id in clipFilter }
        val engines = EngineCatalog.engines().filter {
            engineFilter == null || it.name in engineFilter
        }
        assertTrue("benchmarkClips matched no clips: $clipFilter", clips.isNotEmpty())
        assertTrue("benchmarkEngines matched no engines: $engineFilter", engines.isNotEmpty())

        val runId = "%s_%s".format(
            SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date()),
            Build.MODEL.replace(' ', '-'),
        )
        val entries = mutableListOf<BenchmarkResults.Entry>()

        // A full matrix run takes well over an hour, so results.json is
        // rewritten after every entry: a run cut short by a USB drop, a
        // timeout, or a cancelled build still leaves judgeable data on the
        // device instead of losing the whole session's work.
        fun writeResults() {
            val results = BenchmarkResults(
                runId = runId,
                device = BenchmarkResults.DeviceInfo(Build.MODEL, Build.VERSION.SDK_INT),
                sdkGitSha = InstrumentationRegistry.getArguments()
                    .getString("benchmarkGitSha") ?: "unknown",
                translator = translator.id,
                entries = entries.toList(),
            )
            TestStorage().openOutputFile("results.json").bufferedWriter().use {
                it.write(results.toJson())
            }
        }

        // Full matrix: clip × engine × translation target. Unsupported pairs
        // are recorded as skipped so the report can show the gap.
        //
        // The audio is transcribed once per clip × engine, not once per
        // target. Nothing upstream of translation depends on the target
        // language — capture, ASR and sentence assembly are all driven by the
        // source — so running the whole pipeline again for a second target
        // repeated identical work: it was 39% of the total runtime, and with
        // Whisper at 3.2x real time that is the single largest cost in the
        // matrix. Extra targets now re-translate the captions the first pass
        // produced, which is exactly what the session itself does with them.
        //
        // It is also stricter. Two independent passes produce slightly
        // different transcripts (ASR is not deterministic here), so comparing
        // a clip's Vietnamese and English scores previously meant comparing
        // translations of two different transcripts. Now they share one.
        for (clip in clips) {
            for (engine in engines) {
                val targets = clip.translations.keys.sorted()
                if (clip.language !in engine.languages) {
                    for (target in targets) {
                        entries += BenchmarkResults.Entry(
                            clipId = clip.id ?: "?",
                            engine = engine.name,
                            sourceLang = clip.language ?: "?",
                            targetLang = target,
                            transcript = null,
                            translation = null,
                            captionCount = 0,
                            processingMs = 0,
                            error = null,
                            skipped = "engine has no model for language '${clip.language}'",
                        )
                        writeResults()
                    }
                    continue
                }

                val primary = runClip(context, clip, engine, translator, targets.first())
                entries += primary
                writeResults()

                for (target in targets.drop(1)) {
                    entries += retranslate(context, clip, engine, translator, target, primary)
                    writeResults()
                }
            }
        }

        val failures = entries.filter { it.error != null }
        assertTrue(
            "harness failures (results.json still written):\n" +
                failures.joinToString("\n") { "  ${it.clipId}×${it.engine}: ${it.error}" },
            failures.isEmpty(),
        )
    }

    /**
     * Re-translates [primary]'s captions into [targetLang], reusing its
     * transcript instead of decoding the audio again.
     *
     * Faithful because the SDK translates each assembled caption on its own —
     * see `SublySession.buildFinalCaption` — so feeding the same caption text
     * to the same translator yields the same caption the session would have
     * emitted. `processingMs` therefore covers translation only, and is not
     * comparable with a primary entry's.
     */
    private suspend fun retranslate(
        context: Context,
        clip: DatasetManifest.Clip,
        engine: BenchmarkEngine,
        translator: BenchmarkTranslator,
        targetLang: String,
        primary: BenchmarkResults.Entry,
    ): BenchmarkResults.Entry {
        val startedMs = SystemClock.elapsedRealtime()

        fun entry(
            captions: List<BenchmarkResults.CaptionPair>,
            error: String?,
        ) = BenchmarkResults.Entry(
            clipId = clip.id ?: "?",
            engine = engine.name,
            sourceLang = clip.language ?: "?",
            targetLang = targetLang,
            transcript = primary.transcript,
            translation = captions.joinToString(" ") { it.translated }.ifEmpty { null },
            captions = captions,
            captionCount = captions.size,
            processingMs = SystemClock.elapsedRealtime() - startedMs,
            error = error,
        )

        // A failed primary has no captions to translate; carry its error
        // rather than inventing a second, unrelated failure.
        if (primary.error != null) {
            return entry(emptyList(), "primary pass failed: ${primary.error}")
        }

        return try {
            val engineInstance = translator.factory(context)
            try {
                val prepared = withTimeout(PREPARE_TIMEOUT_MS) {
                    engineInstance.prepareModel(
                        LanguageConfig(source = requireNotNull(clip.language), target = targetLang)
                    ).first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                }
                if (prepared is ModelPrepState.Error) {
                    return entry(emptyList(), "translator prepare failed: ${prepared.cause}")
                }
                entry(
                    primary.captions.map {
                        BenchmarkResults.CaptionPair(
                            original = it.original,
                            translated = engineInstance.translate(it.original).trim(),
                        )
                    },
                    null,
                )
            } finally {
                engineInstance.release()
            }
        } catch (e: Exception) {
            entry(emptyList(), "retranslate exception: $e")
        }
    }

    private suspend fun runClip(
        context: Context,
        clip: DatasetManifest.Clip,
        engine: BenchmarkEngine,
        translator: BenchmarkTranslator,
        targetLang: String,
    ): BenchmarkResults.Entry {
        val startedMs = SystemClock.elapsedRealtime()

        fun entry(
            transcript: String?,
            translation: String?,
            captionCount: Int,
            error: String?,
            captions: List<BenchmarkResults.CaptionPair> = emptyList(),
        ) = BenchmarkResults.Entry(
            clipId = clip.id ?: "?",
            engine = engine.name,
            sourceLang = clip.language ?: "?",
            targetLang = targetLang,
            transcript = transcript,
            translation = translation,
            captions = captions,
            captionCount = captionCount,
            processingMs = SystemClock.elapsedRealtime() - startedMs,
            error = error,
        )

        return try {
            val audioFile = copyAssetToCache(context, requireNotNull(clip.audio))
            try {
                val capture = FileAudioCapture(audioFile)
                val subly = Subly.Builder()
                    .setAsrEngine { engine.factory(context) }
                    .setTranslator { translator.factory(context) }
                    .setAudioCapture { capture }
                    .setPunctuationRestoration(engine.punctuation)
                    .build()

                subly.createSession(
                    LanguageConfig(source = requireNotNull(clip.language), target = targetLang)
                ).use { session ->
                    val finals = mutableListOf<Caption>()
                    var partialsSeen = 0
                    var lastFinalAtMs = SystemClock.elapsedRealtime()
                    val collector = CoroutineScope(Dispatchers.Default).launch {
                        session.captions.collect {
                            if (it.isFinal) {
                                finals += it
                                lastFinalAtMs = SystemClock.elapsedRealtime()
                            } else {
                                partialsSeen++
                            }
                        }
                    }

                    try {
                        session.prepare()
                        val prepared = withTimeout(PREPARE_TIMEOUT_MS) {
                            session.state.first { it is EngineState.Ready || it is EngineState.Error }
                        }
                        if (prepared is EngineState.Error) {
                            return entry(null, null, 0, "prepare failed: ${prepared.error}")
                        }

                        session.start()
                        // The playback window opens at start(), not at prepare —
                        // model downloads must not eat into it.
                        val playbackStartMs = SystemClock.elapsedRealtime()
                        lastFinalAtMs = playbackStartMs

                        // Wait for the capture to say it has emitted its last
                        // frame, then for the pipeline to go quiet. Deriving
                        // "played out" from the clip's duration instead would
                        // start counting before the audio does — the clip is
                        // decoded inside frames(), which took long enough to
                        // cut several seconds off the end of every clip.
                        val clipMs = clip.durationSec * 1_000L +
                            FileAudioCapture.TRAILING_SILENCE_MS
                        val playbackDeadline = playbackStartMs + clipMs + PIPELINE_SLACK_MS
                        while (SystemClock.elapsedRealtime() < playbackDeadline) {
                            val state = session.state.value
                            if (state is EngineState.Error) {
                                return entry(null, null, finals.size, "pipeline failed: ${state.error}")
                            }
                            val playedOut = capture.finished.isCompleted
                            val quiet =
                                SystemClock.elapsedRealtime() - lastFinalAtMs >= engine.quiescenceMs
                            if (playedOut && quiet) break
                            delay(250)
                        }
                        android.util.Log.i(
                            "BenchmarkRunner",
                            "exit clip=${clip.id} engine=${engine.name} " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - playbackStartMs} " +
                                "playedOut=${capture.finished.isCompleted} " +
                                "finals=${finals.size} " +
                                "deadlineHit=${SystemClock.elapsedRealtime() >= playbackDeadline}",
                        )
                        session.stop()
                    } finally {
                        collector.cancel()
                    }

                    if (finals.isEmpty()) {
                        entry(
                            null, null, 0,
                            "pipeline produced no final captions ($partialsSeen partials seen)",
                        )
                    } else {
                        entry(
                            transcript = finals.joinToString(" ") { it.originalText.trim() },
                            translation = finals.joinToString(" ") { it.translatedText.trim() },
                            captionCount = finals.size,
                            error = null,
                            // Boundaries kept as well as the joined text: joining
                            // hides where the SDK actually broke the stream.
                            captions = finals.map {
                                BenchmarkResults.CaptionPair(
                                    original = it.originalText.trim(),
                                    translated = it.translatedText.trim(),
                                )
                            },
                        )
                    }
                }
            } finally {
                audioFile.delete()
            }
        } catch (e: Exception) {
            entry(null, null, 0, "harness exception: $e")
        }
    }

    private fun copyAssetToCache(context: Context, assetPath: String): File {
        val out = File.createTempFile("bench", ".mp4", context.cacheDir)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        out.outputStream().use { dst -> assets.open("dataset/$assetPath").use { it.copyTo(dst) } }
        return out
    }

    private companion object {
        /**
         * First run downloads the ASR model (~41 MB for Vosk) and the
         * translator's. Sized for the LLM translator, whose checkpoint is
         * ~1.6 GB and whose engine init and warm-up are both counted here —
         * ML Kit's whole preparation fits in the first minute of it.
         */
        const val PREPARE_TIMEOUT_MS = 45 * 60 * 1_000L

        /**
         * Extra wall-clock budget beyond the clip for decode/ASR/translate
         * lag. Generous on purpose: on older devices the translation backlog
         * can outlive playback by minutes, and quiescence (not this cap) is
         * the normal exit — the cap only bites when the pipeline hangs.
         */
        const val PIPELINE_SLACK_MS = 180_000L
    }
}
