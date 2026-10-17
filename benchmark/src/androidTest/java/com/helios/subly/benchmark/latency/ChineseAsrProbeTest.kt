package com.helios.subly.benchmark.latency

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.google.gson.GsonBuilder
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.asr.vosk.VoskTranscriber
import com.helios.subly.asr.whisper.WhisperTranscriber
import com.helios.subly.benchmark.FileAudioCapture
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Which recogniser transcribes Mandarin best on this phone.
 *
 * Decodes the same audio with every candidate and reports character error
 * rate (Han characters only, punctuation and spaces ignored) against the
 * reference, plus real-time factor. Audio is decoded once and fed as fast as
 * each engine takes it, so RTF is processing time / audio time.
 *
 * Candidates: the SDK's Vosk and whisper (CPU) as baselines, and four
 * sherpa-onnx Mandarin models: two streaming zipformer transducers (fed
 * chunk by chunk, as live audio would be) and two offline models
 * (SenseVoice, Paraformer) run on Silero-VAD segments, which is how an
 * offline model is used for live captions.
 *
 * Writes `zh_asr_probe.json`. Run:
 * ```
 * ./gradlew :benchmark:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.helios.subly.benchmark.latency.ChineseAsrProbeTest
 * ```
 * Optional `probeAsr` runner argument: comma-separated candidate names.
 */
class ChineseAsrProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val modelRoot = File(context.filesDir, "asr-probe").apply { mkdirs() }

    data class Clip(val id: String, val asset: String, val reference: String)

    data class Result(
        val candidate: String,
        val clip: String,
        val cer: Double,
        val referenceChars: Int,
        val rtf: Double,
        val initMs: Long,
        val transcript: String,
        val error: String? = null,
    )

    private sealed interface Candidate {
        val name: String
    }

    private data class Streaming(
        override val name: String,
        val repo: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
    ) : Candidate

    private data class Offline(override val name: String, val repo: String, val model: String, val kind: String) :
        Candidate

    private data class SdkEngine(override val name: String, val factory: () -> SublyAsr) : Candidate

    private val candidates: List<Candidate> = listOf(
        SdkEngine("vosk-small-cn") { VoskTranscriber(context) },
        SdkEngine("whisper-small-q5_1-cpu") { WhisperTranscriber(context, preferGpu = false) },
        Streaming(
            "zipformer-zh-2025-06-30-int8", "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            encoder = "encoder.int8.onnx", decoder = "decoder.onnx", joiner = "joiner.int8.onnx",
        ),
        Streaming(
            "zipformer-multi-zh-hans-2023-12-13-int8",
            "csukuangfj/sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13",
            encoder = "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
            decoder = "decoder-epoch-20-avg-1-chunk-16-left-128.onnx",
            joiner = "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
        ),
        Offline(
            "sensevoice-2025-09-09-int8", "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
            model = "model.int8.onnx", kind = "sensevoice",
        ),
        Offline(
            "paraformer-zh-2025-10-07-int8", "csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07",
            model = "model.int8.onnx", kind = "paraformer",
        ),
    )

    @Test
    fun probe() = runBlocking {
        val wanted = InstrumentationRegistry.getArguments().getString("probeAsr")?.split(',')?.map { it.trim() }
        val clips = listOf(
            Clip("zh_dialogue_01", "realworld/zh_dialogue_01.mp4", asset("realworld/zh_dialogue_01.transcript.txt")),
            Clip("fleurs_zh_01", "dataset/audio/fleurs_zh_01.mp4", asset("dataset/references/fleurs_zh_01.transcript.txt")),
            Clip("fleurs_zh_02", "dataset/audio/fleurs_zh_02.mp4", asset("dataset/references/fleurs_zh_02.transcript.txt")),
        )
        val audio = clips.associate { it.id to decode(it.asset) }
        val vadPath = download("vad", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models", "silero_vad.onnx")

        val results = ArrayList<Result>()
        for (candidate in candidates.filter { wanted == null || it.name in wanted }) {
            val outcome = runCatching { transcribeAll(candidate, clips, audio, vadPath) }
            outcome.getOrElse { e ->
                Log.e(TAG, "${candidate.name} failed", e)
                clips.map { Result(candidate.name, it.id, 1.0, 0, 0.0, 0, "", e.toString()) }
            }.forEach { r ->
                results += r
                Log.i(TAG, "probe ${r.candidate} ${r.clip} cer=${"%.3f".format(r.cer)} rtf=${"%.3f".format(r.rtf)} " +
                    "init=${r.initMs}ms text=\"${r.transcript}\" err=${r.error}")
            }
        }
        TestStorage().openOutputFile("zh_asr_probe.json").bufferedWriter().use {
            it.write(GsonBuilder().setPrettyPrinting().create().toJson(results))
        }
    }

    private suspend fun transcribeAll(
        candidate: Candidate,
        clips: List<Clip>,
        audio: Map<String, FloatArray>,
        vadPath: String,
    ): List<Result> = when (candidate) {
        is SdkEngine -> {
            val engine = candidate.factory()
            try {
                val t0 = SystemClock.elapsedRealtime()
                val state = withTimeout(PREPARE_TIMEOUT_MS) {
                    engine.prepareModel(LanguageConfig("zh", "vi"))
                        .first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                }
                check(state is ModelPrepState.Ready) { "prepare failed: $state" }
                val initMs = SystemClock.elapsedRealtime() - t0
                clips.map { clip ->
                    val pcm = audio.getValue(clip.id)
                    val (text, ms) = timed { sdkTranscribe(engine, pcm) }
                    result(candidate.name, clip, text, ms, pcm.size, initMs)
                }
            } finally {
                engine.release()
            }
        }

        is Streaming -> {
            val dir = listOf(candidate.encoder, candidate.decoder, candidate.joiner, "tokens.txt")
                .map { download(candidate.name, hf(candidate.repo), it) }
                .first().let { File(it).parent!! }
            val t0 = SystemClock.elapsedRealtime()
            val recognizer = OnlineRecognizer(
                assetManager = null,
                config = OnlineRecognizerConfig().apply {
                    modelConfig = OnlineModelConfig().apply {
                        transducer.encoder = "$dir/${candidate.encoder}"
                        transducer.decoder = "$dir/${candidate.decoder}"
                        transducer.joiner = "$dir/${candidate.joiner}"
                        tokens = "$dir/tokens.txt"
                        numThreads = THREADS
                    }
                    enableEndpoint = true
                    decodingMethod = "greedy_search"
                },
            )
            val initMs = SystemClock.elapsedRealtime() - t0
            try {
                clips.map { clip ->
                    val pcm = audio.getValue(clip.id)
                    val (text, ms) = timed { streamingTranscribe(recognizer, pcm) }
                    result(candidate.name, clip, text, ms, pcm.size, initMs)
                }
            } finally {
                recognizer.release()
            }
        }

        is Offline -> {
            val model = download(candidate.name, hf(candidate.repo), candidate.model)
            val tokens = download(candidate.name, hf(candidate.repo), "tokens.txt")
            val t0 = SystemClock.elapsedRealtime()
            val recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig().apply {
                    modelConfig = OfflineModelConfig().apply {
                        when (candidate.kind) {
                            "sensevoice" -> {
                                senseVoice.model = model
                                senseVoice.language = "zh"
                                senseVoice.useInverseTextNormalization = true
                            }
                            else -> paraformer.model = model
                        }
                        this.tokens = tokens
                        numThreads = THREADS
                    }
                },
            )
            val initMs = SystemClock.elapsedRealtime() - t0
            try {
                clips.map { clip ->
                    val pcm = audio.getValue(clip.id)
                    val (text, ms) = timed { offlineTranscribe(recognizer, vadPath, pcm) }
                    result(candidate.name, clip, text, ms, pcm.size, initMs)
                }
            } finally {
                recognizer.release()
            }
        }
    }

    private suspend fun sdkTranscribe(engine: SublyAsr, pcm: FloatArray): String {
        val shorts = ShortArray(pcm.size) { (pcm[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        // Two seconds of trailing silence so end-of-speech segmenters flush,
        // as FileAudioCapture appends for the accuracy benchmark.
        val padded = shorts + ShortArray(2 * SAMPLE_RATE)
        val frames = flow {
            var t = 0L
            var i = 0
            while (i < padded.size) {
                val chunk = padded.copyOfRange(i, minOf(i + FRAME, padded.size))
                emit(AudioFrame(chunk, SAMPLE_RATE, 1, t, chunk.maxOf { abs(it.toInt()) }))
                t += chunk.size * 1000L / SAMPLE_RATE
                i += FRAME
            }
        }
        return engine.transcribe(frames).filterIsInstance<AsrResult.Final>().toList()
            .joinToString(" ") { it.text }
    }

    private fun streamingTranscribe(recognizer: OnlineRecognizer, pcm: FloatArray): String {
        val stream = recognizer.createStream("")
        val out = StringBuilder()
        fun drain() {
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            if (recognizer.isEndpoint(stream)) {
                out.append(recognizer.getResult(stream).text).append(' ')
                recognizer.reset(stream)
            }
        }
        var i = 0
        while (i < pcm.size) {
            stream.acceptWaveform(pcm.copyOfRange(i, minOf(i + FRAME, pcm.size)), SAMPLE_RATE)
            drain()
            i += FRAME
        }
        stream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        out.append(recognizer.getResult(stream).text)
        stream.release()
        return out.toString().trim()
    }

    private fun offlineTranscribe(recognizer: OfflineRecognizer, vadPath: String, pcm: FloatArray): String {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig().apply {
                sileroVadModelConfig.model = vadPath
                sileroVadModelConfig.threshold = 0.5f
                sileroVadModelConfig.minSilenceDuration = 0.25f
                sileroVadModelConfig.minSpeechDuration = 0.25f
                sileroVadModelConfig.maxSpeechDuration = 8f
                sampleRate = SAMPLE_RATE
                numThreads = 1
            },
        )
        val out = StringBuilder()
        fun drain() {
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                val stream = recognizer.createStream()
                stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                recognizer.decode(stream)
                out.append(recognizer.getResult(stream).text).append(' ')
                stream.release()
            }
        }
        var i = 0
        while (i + VAD_WINDOW <= pcm.size) {
            vad.acceptWaveform(pcm.copyOfRange(i, i + VAD_WINDOW))
            drain()
            i += VAD_WINDOW
        }
        vad.flush()
        drain()
        vad.release()
        return out.toString().trim()
    }

    private fun result(name: String, clip: Clip, text: String, ms: Long, samples: Int, initMs: Long) =
        Result(
            candidate = name,
            clip = clip.id,
            cer = cer(text, clip.reference),
            referenceChars = han(clip.reference).length,
            rtf = ms / (samples * 1000.0 / SAMPLE_RATE),
            initMs = initMs,
            transcript = text,
        )

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = SystemClock.elapsedRealtime()
        val value = block()
        return value to SystemClock.elapsedRealtime() - t0
    }

    private fun asset(path: String): String =
        instrumentation.context.assets.open(path).bufferedReader().readText()

    private fun decode(assetPath: String): FloatArray {
        val file = File.createTempFile("probe", ".mp4", context.cacheDir)
        try {
            file.outputStream().use { out -> instrumentation.context.assets.open(assetPath).use { it.copyTo(out) } }
            val pcm = FileAudioCapture(file).decodeMono16k()
            return FloatArray(pcm.size) { pcm[it] / 32768f }
        } finally {
            file.delete()
        }
    }

    private fun hf(repo: String) = "https://huggingface.co/$repo/resolve/main"

    private suspend fun download(dir: String, base: String, name: String): String {
        val target = File(File(modelRoot, dir).apply { mkdirs() }, name)
        if (!target.exists() || target.length() == 0L) {
            OkHttpModelDownloader().downloadModel("$base/$name", target).toList()
            check(target.exists() && target.length() > 0) { "download failed: $base/$name" }
        }
        return target.absolutePath
    }

    companion object {
        private const val TAG = "ZhAsrProbe"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME = 1_600
        private const val VAD_WINDOW = 512
        private const val THREADS = 4
        private const val PREPARE_TIMEOUT_MS = 20 * 60 * 1_000L

        private fun han(s: String) = s.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

        /** Character error rate over Han characters. */
        fun cer(hypothesis: String, reference: String): Double {
            val h = han(hypothesis)
            val r = han(reference)
            if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
            var prev = IntArray(r.length + 1) { it }
            for (i in 1..h.length) {
                val cur = IntArray(r.length + 1)
                cur[0] = i
                for (j in 1..r.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (h[i - 1] == r[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[r.length].toDouble() / r.length
        }
    }
}
