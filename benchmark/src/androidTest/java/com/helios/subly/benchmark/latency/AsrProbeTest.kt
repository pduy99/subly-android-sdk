package com.helios.subly.benchmark.latency

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.google.gson.GsonBuilder
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.asr.vosk.VoskTranscriber
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
import java.util.Locale
import kotlin.math.abs

/**
 * Which offline recogniser transcribes each supported language best on this
 * phone: English, Chinese, Japanese, Spanish and Vietnamese.
 *
 * Every candidate decodes the same audio; the report is the error rate
 * against the reference (character error for zh/ja, word error for en/es/vi,
 * punctuation and case ignored), real-time factor and init time. Streaming
 * models are fed chunk by chunk as live audio would be; offline models run on
 * Silero-VAD segments, which is how an offline model makes live captions.
 *
 * Writes `asr_probe.json`. Runner arguments: `probeLanguages` (e.g. `ja,vi`)
 * and `probeAsr` (candidate names) narrow the run; `probeClips` (default 2,
 * up to 5) sets FLEURS clips per language; `probeVadSilence` and
 * `probeVadMax` (seconds) set the offline models' segmentation.
 */
class AsrProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val modelRoot = File(context.filesDir, "asr-probe").apply { mkdirs() }

    /** VAD segmentation for offline models: `probeVadSilence` / `probeVadMax` runner arguments. */
    private var vadMinSilence = 0.3f
    private var vadMaxSpeech = 8f

    data class Clip(val id: String, val language: String, val asset: String, val reference: String)

    data class Result(
        val candidate: String,
        val language: String,
        val clip: String,
        val errorRate: Double,
        val metric: String,
        val referenceUnits: Int,
        val rtf: Double,
        val initMs: Long,
        val downloadMb: Double,
        val transcript: String,
        val error: String? = null,
    )

    /** A repo file to fetch, with its role. */
    private data class Files(val repo: String, val revision: String, val names: List<String>)

    private sealed interface Candidate {
        val name: String
        val languages: Set<String>
    }

    private data class Streaming(
        override val name: String, override val languages: Set<String>, val files: Files,
        val encoder: String, val decoder: String, val joiner: String,
    ) : Candidate

    private data class OfflineTransducer(
        override val name: String, override val languages: Set<String>, val files: Files,
        val encoder: String, val decoder: String, val joiner: String, val modelType: String = "",
    ) : Candidate

    private data class Moonshine(override val name: String, override val languages: Set<String>, val files: Files) : Candidate
    private data class SenseVoice(override val name: String, override val languages: Set<String>, val files: Files) : Candidate
    private data class Qwen3Asr(override val name: String, override val languages: Set<String>, val files: Files) : Candidate
    private data class SdkEngine(override val name: String, override val languages: Set<String>, val factory: () -> SublyAsr) : Candidate

    private fun moonshine(lang: String, repo: String, rev: String) = Moonshine(
        "moonshine-base-$lang", setOf(lang),
        Files(repo, rev, listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt")),
    )

    private val candidates: List<Candidate> = listOf(
        // Baseline: what the app uses today for most languages.
        SdkEngine("vosk-small", setOf("en", "zh", "ja", "es", "vi")) { VoskTranscriber(context) },
        // Chinese (two measured before on the dialogue clip).
        SenseVoice(
            "sensevoice-int8", setOf("zh", "en", "ja"),
            Files("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
                "355f4d4884d8afd08aef04b9007a8556d7b463b2", listOf("model.int8.onnx", "tokens.txt")),
        ),
        Streaming(
            "zipformer-zh-streaming", setOf("zh"),
            Files("csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
                "ad658fa0201659a09ea3c176129a191c77ecae8f",
                listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")),
            "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
        ),
        moonshine("zh", "csukuangfj2/sherpa-onnx-moonshine-base-zh-quantized-2026-02-27", "19042e30b8680b101d0db3b0b8cabc84ae954b7f"),
        // English.
        Streaming(
            "parakeet-unified-0.6b-streaming", setOf("en"),
            Files("csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-240ms",
                "188005017eb5516adf14e7355fbd919e6a36d704",
                listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")),
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
        ),
        Streaming(
            "kroko-en-streaming", setOf("en"),
            Files("csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
                "572aaf4e2e0c603c3fc2a574d096e755a178faa1",
                listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")),
            "encoder.onnx", "decoder.onnx", "joiner.onnx",
        ),
        OfflineTransducer(
            "parakeet-tdt-0.6b-v2", setOf("en"),
            Files("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", "1ab9323565ddb038682214b292f588070a538ce2",
                listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")),
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", modelType = "nemo_transducer",
        ),
        OfflineTransducer(
            "parakeet-tdt-0.6b-v3", setOf("en", "es"),
            Files("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78",
                listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")),
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", modelType = "nemo_transducer",
        ),
        moonshine("en", "csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27", "main"),
        Streaming(
            "zipformer-en-streaming", setOf("en"),
            Files("csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26", "main",
                listOf("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                    "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                    "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", "tokens.txt")),
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        ),
        // Japanese.
        OfflineTransducer(
            "reazonspeech-k2-v2", setOf("ja"),
            Files("csukuangfj/reazonspeech-k2-v2", "7ebed8f70b8ef67f656aa08e3084e5885202d0e6",
                listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                    "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")),
            "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx",
        ),
        moonshine("ja", "csukuangfj2/sherpa-onnx-moonshine-base-ja-quantized-2026-02-27", "main"),
        // Spanish.
        Streaming(
            "kroko-es-streaming", setOf("es"),
            Files("csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
                "20cf7a4921613397841d31168796cade5b866585",
                listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")),
            "encoder.onnx", "decoder.onnx", "joiner.onnx",
        ),
        moonshine("es", "csukuangfj2/sherpa-onnx-moonshine-base-es-quantized-2026-02-27", "main"),
        // Vietnamese.
        OfflineTransducer(
            "zipformer-vi-2025-04-20", setOf("vi"),
            Files("csukuangfj/sherpa-onnx-zipformer-vi-int8-2025-04-20", "b2745a435379992ad3f299635468db0c34918e1e",
                listOf("encoder-epoch-12-avg-8.int8.onnx", "decoder-epoch-12-avg-8.onnx",
                    "joiner-epoch-12-avg-8.int8.onnx", "tokens.txt")),
            "encoder-epoch-12-avg-8.int8.onnx", "decoder-epoch-12-avg-8.onnx", "joiner-epoch-12-avg-8.int8.onnx",
        ),
        OfflineTransducer(
            "zipformer-vi-30M", setOf("vi"),
            Files("csukuangfj2/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09", "83e140db6d23fbb8480fd5fb868f74ab80e7092c",
                listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")),
            "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx",
        ),
        moonshine("vi", "csukuangfj2/sherpa-onnx-moonshine-base-vi-quantized-2026-02-27", "main"),
        // All five in one model.
        Qwen3Asr(
            "qwen3-asr-0.6b", setOf("en", "zh", "ja", "es", "vi"),
            Files("csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25", "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a",
                listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
                    "tokenizer/merges.txt", "tokenizer/tokenizer_config.json", "tokenizer/vocab.json")),
        ),
    )

    @Test
    fun probe() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val langs = args.getString("probeLanguages")?.split(',')?.map { it.trim() }?.toSet()
            ?: setOf("en", "zh", "ja", "es", "vi")
        val wanted = args.getString("probeAsr")?.split(',')?.map { it.trim() }?.toSet()

        val clipCount = args.getString("probeClips")?.toInt() ?: 2
        vadMaxSpeech = args.getString("probeVadMax")?.toFloat() ?: vadMaxSpeech
        vadMinSilence = args.getString("probeVadSilence")?.toFloat() ?: vadMinSilence
        val clips = langs.flatMap { lang ->
            (1..clipCount).map { n ->
                val id = "fleurs_${lang}_0$n"
                Clip(id, lang, "dataset/audio/$id.mp4", asset("dataset/references/$id.transcript.txt"))
            }
        } + if ("zh" in langs) listOf(
            Clip("zh_dialogue_01", "zh", "realworld/zh_dialogue_01.mp4", asset("realworld/zh_dialogue_01.transcript.txt"))
        ) else emptyList()
        val audio = clips.associate { it.id to decode(it.asset) }
        val vadPath = download("vad", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models", "silero_vad.onnx")

        val results = ArrayList<Result>()
        for (candidate in candidates.filter { (wanted == null || it.name in wanted) && it.languages.any { l -> l in langs } }) {
            val mine = clips.filter { it.language in candidate.languages }
            val outcome = runCatching { run(candidate, mine, audio, vadPath) }
            outcome.getOrElse { e ->
                Log.e(TAG, "${candidate.name} failed", e)
                mine.map { Result(candidate.name, it.language, it.id, 1.0, metricFor(it.language), 0, 0.0, 0, 0.0, "", e.toString()) }
            }.forEach { r ->
                results += r
                Log.i(TAG, "probe ${r.candidate} ${r.clip} ${r.metric}=${"%.3f".format(r.errorRate)} " +
                    "rtf=${"%.3f".format(r.rtf)} init=${r.initMs}ms dl=${"%.0f".format(r.downloadMb)}MB " +
                    "text=\"${r.transcript.take(400)}\" err=${r.error}")
            }
        }
        TestStorage().openOutputFile("asr_probe.json").bufferedWriter().use {
            it.write(GsonBuilder().setPrettyPrinting().create().toJson(results))
        }
    }

    private suspend fun run(c: Candidate, clips: List<Clip>, audio: Map<String, FloatArray>, vadPath: String): List<Result> {
        if (c is SdkEngine) return clips.groupBy { it.language }.flatMap { (lang, group) -> runSdk(c, lang, group, audio) }

        val files = when (c) {
            is Streaming -> c.files
            is OfflineTransducer -> c.files
            is Moonshine -> c.files
            is SenseVoice -> c.files
            is Qwen3Asr -> c.files
            is SdkEngine -> error("handled above")
        }
        val dir = File(modelRoot, c.name)
        files.names.forEach { download(c.name, "https://huggingface.co/${files.repo}/resolve/${files.revision}", it) }
        val downloadMb = files.names.sumOf { File(dir, it).length() } / 1e6
        val p = { name: String -> File(dir, name).absolutePath }

        return clips.groupBy { it.language }.flatMap { (lang, group) ->
            val t0 = SystemClock.elapsedRealtime()
            if (c is Streaming) {
                val recognizer = OnlineRecognizer(assetManager = null, config = OnlineRecognizerConfig().apply {
                    modelConfig = OnlineModelConfig().apply {
                        transducer.encoder = p(c.encoder); transducer.decoder = p(c.decoder); transducer.joiner = p(c.joiner)
                        tokens = p("tokens.txt"); numThreads = THREADS
                    }
                    enableEndpoint = true
                    decodingMethod = "greedy_search"
                })
                val initMs = SystemClock.elapsedRealtime() - t0
                try {
                    group.map { clip ->
                        val pcm = audio.getValue(clip.id)
                        val (text, ms) = timed { streamingTranscribe(recognizer, pcm) }
                        result(c.name, clip, text, ms, pcm.size, initMs, downloadMb)
                    }
                } finally {
                    recognizer.release()
                }
            } else {
                val recognizer = OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig().apply {
                    modelConfig = OfflineModelConfig().apply {
                        when (c) {
                            is OfflineTransducer -> {
                                transducer.encoder = p(c.encoder); transducer.decoder = p(c.decoder); transducer.joiner = p(c.joiner)
                                tokens = p("tokens.txt")
                                if (c.modelType.isNotEmpty()) modelType = c.modelType
                            }
                            is Moonshine -> {
                                moonshine.encoder = p("encoder_model.ort"); moonshine.mergedDecoder = p("decoder_model_merged.ort")
                                tokens = p("tokens.txt")
                            }
                            is SenseVoice -> {
                                senseVoice.model = p("model.int8.onnx"); senseVoice.language = lang
                                senseVoice.useInverseTextNormalization = true
                                tokens = p("tokens.txt")
                            }
                            is Qwen3Asr -> {
                                qwen3Asr.convFrontend = p("conv_frontend.onnx"); qwen3Asr.encoder = p("encoder.int8.onnx")
                                qwen3Asr.decoder = p("decoder.int8.onnx"); qwen3Asr.tokenizer = p("tokenizer")
                            }
                        }
                        numThreads = THREADS
                    }
                })
                val initMs = SystemClock.elapsedRealtime() - t0
                try {
                    group.map { clip ->
                        val pcm = audio.getValue(clip.id)
                        val (text, ms) = timed { offlineTranscribe(recognizer, vadPath, pcm) }
                        result(c.name, clip, text, ms, pcm.size, initMs, downloadMb)
                    }
                } finally {
                    recognizer.release()
                }
            }
        }
    }

    private suspend fun runSdk(c: SdkEngine, lang: String, group: List<Clip>, audio: Map<String, FloatArray>): List<Result> {
        val engine = c.factory()
        try {
            val t0 = SystemClock.elapsedRealtime()
            val state = withTimeout(PREPARE_TIMEOUT_MS) {
                engine.prepareModel(LanguageConfig(lang, "en")).first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
            }
            check(state is ModelPrepState.Ready) { "prepare failed: $state" }
            val initMs = SystemClock.elapsedRealtime() - t0
            return group.map { clip ->
                val pcm = audio.getValue(clip.id)
                val (text, ms) = timed { sdkTranscribe(engine, pcm) }
                result(c.name, clip, text, ms, pcm.size, initMs, 0.0)
            }
        } finally {
            engine.release()
        }
    }

    private suspend fun sdkTranscribe(engine: SublyAsr, pcm: FloatArray): String {
        val shorts = ShortArray(pcm.size) { (pcm[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
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
        return engine.transcribe(frames).filterIsInstance<AsrResult.Final>().toList().joinToString(" ") { it.text }
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
        val vad = Vad(assetManager = null, config = VadModelConfig().apply {
            sileroVadModelConfig.model = vadPath
            sileroVadModelConfig.threshold = 0.5f
            sileroVadModelConfig.minSilenceDuration = vadMinSilence
            sileroVadModelConfig.minSpeechDuration = 0.25f
            sileroVadModelConfig.maxSpeechDuration = vadMaxSpeech
            sampleRate = SAMPLE_RATE
            numThreads = 1
        })
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

    private fun result(name: String, clip: Clip, text: String, ms: Long, samples: Int, initMs: Long, downloadMb: Double): Result {
        val (rate, units) = errorRate(text, clip.reference, clip.language)
        return Result(name, clip.language, clip.id, rate, metricFor(clip.language), units,
            ms / (samples * 1000.0 / SAMPLE_RATE), initMs, downloadMb, text)
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = SystemClock.elapsedRealtime()
        val value = block()
        return value to SystemClock.elapsedRealtime() - t0
    }

    private fun asset(path: String): String = instrumentation.context.assets.open(path).bufferedReader().readText()

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

    private suspend fun download(dir: String, base: String, name: String): String {
        val target = File(File(modelRoot, dir), name).apply { parentFile?.mkdirs() }
        if (!target.exists() || target.length() == 0L) {
            OkHttpModelDownloader().downloadModel("$base/$name", target).toList()
            check(target.exists() && target.length() > 0) { "download failed: $base/$name" }
        }
        return target.absolutePath
    }

    companion object {
        private const val TAG = "AsrProbe"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME = 1_600
        private const val VAD_WINDOW = 512
        private const val THREADS = 4
        private const val PREPARE_TIMEOUT_MS = 20 * 60 * 1_000L

        fun metricFor(language: String) = if (language in CHARACTER_LANGUAGES) "cer" else "wer"

        private val CHARACTER_LANGUAGES = setOf("zh", "ja")

        /** Error rate and reference length: characters for zh/ja, words otherwise. */
        fun errorRate(hypothesis: String, reference: String, language: String): Pair<Double, Int> {
            val h = units(hypothesis, language)
            val r = units(reference, language)
            if (r.isEmpty()) return (if (h.isEmpty()) 0.0 else 1.0) to 0
            var prev = IntArray(r.size + 1) { it }
            for (i in 1..h.size) {
                val cur = IntArray(r.size + 1)
                cur[0] = i
                for (j in 1..r.size) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (h[i - 1] == r[j - 1]) 0 else 1)
                }
                prev = cur
            }
            return prev[r.size].toDouble() / r.size to r.size
        }

        /** Letters and digits only, lower-cased; per character for zh/ja, per word otherwise. */
        private fun units(text: String, language: String): List<String> {
            val clean = text.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            return if (language in CHARACTER_LANGUAGES) clean.filter { !it.isWhitespace() }.map { it.toString() }
            else clean.split(' ').filter { it.isNotEmpty() }
        }
    }
}
