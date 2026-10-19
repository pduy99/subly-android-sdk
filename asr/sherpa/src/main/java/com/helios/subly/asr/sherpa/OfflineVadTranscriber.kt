package com.helios.subly.asr.sherpa

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AccelerationAware
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.BackendAttempt
import com.helios.subly.core.model.DelegateType
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform

/**
 * Offline recognition, made live with voice activity detection.
 *
 * An offline model decodes a whole utterance at once, which is what makes it
 * accurate, and the ones in the catalog are fast enough (0.06-0.13x real time
 * measured on a Galaxy S10) to do so repeatedly. So:
 *  - Silero VAD cuts the audio into speech segments of at most
 *    [MAX_SEGMENT_S] seconds; each finished segment is decoded once and
 *    emitted as an [AsrResult.Final];
 *  - while a segment is still being spoken, the speech so far is re-decoded
 *    about once a second and emitted as an [AsrResult.Partial], backing off
 *    when decoding takes longer, so partials never fall behind the audio.
 *
 * The model is [SherpaOnnxModel.bestFor] the source language (an
 * [SherpaModelKind.OFFLINE_ASR] one), or a pinned model. SenseVoice output
 * carries digits (inverse text normalisation on); Chinese output gets
 * punctuation from [ChinesePunctuator]; all-caps output is put in sentence
 * case.
 *
 * The sherpa-onnx runtime is compileOnly in this module: the app must ship
 * the sherpa-onnx AAR.
 */
class OfflineVadTranscriber internal constructor(
    private val context: Context,
    private val modelLoaderFactory: SherpaOnnxModelLoaderFactory,
    /** A fixed model, or null to pick one per source language at prepare. */
    private val pinnedModel: SherpaOnnxModel?,
) : SublyAsr, AccelerationAware {

    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(context, SherpaOnnxModelLoaderFactory.of(modelDownloader), pinnedModel = null)

    init {
        require(pinnedModel == null || pinnedModel.kind == SherpaModelKind.OFFLINE_ASR) {
            "$pinnedModel is not an offline recogniser"
        }
    }

    /** Each final is a VAD segment: one utterance, ready to caption as is. */
    override val emitsCompleteUtterances: Boolean = true

    @Volatile
    override var accelerationReport: AccelerationReport? = null
        private set

    /** No offline model here punctuates Chinese; restore 。，？. */
    private val punctuator = ChinesePunctuator(context, modelLoaderFactory)

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var model: SherpaOnnxModel? = null
    private var vadModelPath: String? = null
    private var language = ""

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)
        val primary = config.source.substringBefore('-').lowercase()
        val wanted = pinnedModel ?: SherpaOnnxModel.bestFor(config.source)
            ?.takeIf { it.kind == SherpaModelKind.OFFLINE_ASR }
        val decodeLanguage = wanted?.let { decodingLanguage(it, config.source) }
        if (wanted == null || decodeLanguage == null) {
            emit(ModelPrepState.Error(IllegalArgumentException(
                "No offline recogniser for '${config.source}'. Supported: ${supportedLanguages()}."
            )))
            return@flow
        }

        synchronized(lock) {
            val live = recognizer
            if (live != null && model == wanted) {
                // SenseVoice: the language is a decoding option, not a model.
                if (decodeLanguage != language && wanted.offlineFamily == OfflineFamily.SENSE_VOICE) {
                    live.setConfig(recognizerConfig(wanted, java.io.File(live.config.modelConfig.tokens).parentFile!!, decodeLanguage))
                }
                language = decodeLanguage
            } else if (live != null) {
                // Another language's model: free it before loading this one.
                live.release()
                recognizer = null
            }
        }
        val needsPunctuator = primary == "zh" || decodeLanguage == "yue"
        if (recognizer != null) {
            // A SenseVoice switch into Chinese: punctuation is new.
            if (needsPunctuator) punctuator.prepare().collect { emit(ModelPrepState.Preparing(it)) }
            emit(ModelPrepState.Ready)
            return@flow
        }

        val loader = modelLoaderFactory.create(context)
        // Model: 0 -> 90%. VAD (0.6 MB): 90 -> 92%. Chinese punctuation: 92 -> 95%.
        loader.provisionWithProgress(wanted).collect { emit(ModelPrepState.Preparing(it * 0.9f)) }
        loader.provisionWithProgress(SherpaOnnxModel.SILERO_VAD).collect { emit(ModelPrepState.Preparing(0.9f + it * 0.02f)) }
        if (needsPunctuator) punctuator.prepare().collect { emit(ModelPrepState.Preparing(0.92f + it * 0.03f)) }
        if (!loader.isReady(wanted) || !loader.isReady(SherpaOnnxModel.SILERO_VAD)) {
            emit(ModelPrepState.Error(IllegalStateException(
                "${wanted.name} model download did not complete - check connectivity and retry."
            )))
            return@flow
        }

        val modelDir = loader.downloadTarget(wanted)
        val vad = java.io.File(loader.downloadTarget(SherpaOnnxModel.SILERO_VAD), "silero_vad.onnx").absolutePath

        val start = SystemClock.elapsedRealtime()
        val created = runCatching {
            OfflineRecognizer(assetManager = null, config = recognizerConfig(wanted, modelDir, decodeLanguage))
        }.getOrElse { error ->
            emit(ModelPrepState.Error(IllegalStateException("${wanted.name} init failed", error)))
            return@flow
        }
        val initMs = SystemClock.elapsedRealtime() - start
        synchronized(lock) {
            recognizer = created
            model = wanted
            vadModelPath = vad
            language = decodeLanguage
        }
        accelerationReport = AccelerationReport(
            component = "asr:${wanted.dirName}",
            active = DelegateType.CPU,
            attempts = listOf(BackendAttempt(DelegateType.CPU, succeeded = true, initMs = initMs)),
        )
        Log.i(TAG, "${wanted.name} ready in ${initMs}ms, language=$decodeLanguage")
        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> {
        val vadPath = vadModelPath
        if (recognizer == null || vadPath == null) {
            return flow {
                throw IllegalStateException("Offline recogniser not prepared. Collect prepareModel() to Ready before transcribe().")
            }
        }

        // Segmentation is per model: see SherpaOnnxModel.vadMinSilenceSec.
        val live = model
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig().apply {
                sileroVadModelConfig.model = vadPath
                sileroVadModelConfig.threshold = VAD_THRESHOLD
                sileroVadModelConfig.minSilenceDuration = live?.vadMinSilenceSec ?: MIN_SILENCE_S
                sileroVadModelConfig.minSpeechDuration = MIN_SPEECH_S
                sileroVadModelConfig.maxSpeechDuration = live?.maxSegmentSec ?: MAX_SEGMENT_S
                sileroVadModelConfig.windowSize = VAD_WINDOW
                sampleRate = SAMPLE_RATE
                numThreads = 1
            },
        )
        var pending = FloatArray(0)
        var speech = FloatArray(0)
        var lastPartialAt = 0L
        var partialIntervalMs = MIN_PARTIAL_INTERVAL_MS
        var lastPartial = ""

        suspend fun kotlinx.coroutines.flow.FlowCollector<AsrResult>.drainSegments() {
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                val t0 = SystemClock.elapsedRealtime()
                val text = decode(segment.samples)
                Log.i(BENCH_TAG, "offline_segment ms=${segment.samples.size * 1000L / SAMPLE_RATE} " +
                    "decode_ms=${SystemClock.elapsedRealtime() - t0}")
                // Transcript content only when explicitly opted in (VERBOSE).
                if (Log.isLoggable(BENCH_TAG, Log.VERBOSE)) Log.v(BENCH_TAG, "offline_segment_text \"$text\"")
                speech = FloatArray(0)
                lastPartial = ""
                // NOTE: never log transcript content — it is end-user speech.
                if (text.any { it.isLetterOrDigit() }) {
                    emit(AsrResult.Final(finish(text)))
                }
            }
        }

        return frames
            .transform { frame ->
                val mono = toMono16k(frame)
                if (mono.isEmpty()) return@transform
                pending += mono
                while (pending.size >= VAD_WINDOW) {
                    val window = pending.copyOfRange(0, VAD_WINDOW)
                    pending = pending.copyOfRange(VAD_WINDOW, pending.size)
                    vad.acceptWaveform(window)
                    if (vad.isSpeechDetected()) speech += window else if (vad.empty()) speech = FloatArray(0)
                    drainSegments()
                }

                // Live partial: re-decode the segment still being spoken.
                val now = SystemClock.elapsedRealtime()
                if (vad.isSpeechDetected() && speech.size >= MIN_PARTIAL_SAMPLES &&
                    now - lastPartialAt >= partialIntervalMs
                ) {
                    val t0 = SystemClock.elapsedRealtime()
                    val text = decode(speech)
                    val decodeMs = SystemClock.elapsedRealtime() - t0
                    lastPartialAt = SystemClock.elapsedRealtime()
                    // Stay well ahead of real time: wait at least twice as
                    // long as the last decode took before the next one.
                    partialIntervalMs = maxOf(MIN_PARTIAL_INTERVAL_MS, decodeMs * 2)
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        emit(AsrResult.Partial(finish(text, partial = true)))
                    }
                }
            }
            .onCompletion { cause ->
                if (cause == null) {
                    // End of audio: whatever is still being spoken is the
                    // last segment.
                    if (pending.isNotEmpty()) vad.acceptWaveform(pending)
                    vad.flush()
                    drainSegments()
                }
                vad.release()
            }
            .flowOn(Dispatchers.Default)
    }

    /** Chinese punctuation, or sentence case for an all-caps model. */
    private fun finish(text: String, partial: Boolean = false): String = when (language) {
        "zh", "yue" -> if (partial) punctuator.punctuatePartial(text) else punctuator.punctuate(text)
        // Each segment follows a pause, and these models emit no
        // terminators, so whether it opens a sentence is unknown: stay
        // lower-case (see CasingNormalizer).
        else -> CasingNormalizer.normalise(text)
    }

    private fun decode(samples: FloatArray): String = synchronized(lock) {
        val live = recognizer ?: return ""
        val stream = live.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            live.decode(stream)
            live.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun release() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            model = null
        }
        punctuator.release()
    }

    override fun supportedLanguages(): List<String> =
        pinnedModel?.languages?.toList()
            ?: SherpaOnnxModel.bestLanguages.filter { SherpaOnnxModel.bestFor(it)?.kind == SherpaModelKind.OFFLINE_ASR }

    private fun recognizerConfig(model: SherpaOnnxModel, dir: java.io.File, language: String) = OfflineRecognizerConfig().apply {
        fun file(prefix: String) = dir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            // Prefer the int8 build when both are present.
            ?.minByOrNull { if (".int8." in it.name) 0 else 1 }
            ?.absolutePath
            ?: throw IllegalStateException("$prefix*.onnx missing in $dir")
        modelConfig = OfflineModelConfig().apply {
            when (model.offlineFamily) {
                OfflineFamily.SENSE_VOICE -> {
                    senseVoice.model = file("model")
                    senseVoice.language = language
                    senseVoice.useInverseTextNormalization = true
                }
                OfflineFamily.TRANSDUCER -> {
                    transducer.encoder = file("encoder")
                    transducer.decoder = file("decoder")
                    transducer.joiner = file("joiner")
                }
                null -> error("${model.name} is not an offline recogniser")
            }
            tokens = java.io.File(dir, "tokens.txt").absolutePath
            numThreads = model.numThreads
        }
    }

    /** The model's language option for a BCP-47 tag, or null if it does not cover it. */
    private fun decodingLanguage(model: SherpaOnnxModel, tag: String): String? {
        val primary = tag.substringBefore('-').lowercase()
        val cantonese = primary == "yue" || tag.lowercase() in setOf("zh-hk", "zh-mo")
        return when {
            cantonese && "yue" in model.languages -> "yue"
            primary in model.languages -> primary
            else -> null
        }
    }

    /** Mono float PCM at 16 kHz from a capture frame (int16, mono or stereo). */
    private fun toMono16k(frame: AudioFrame): FloatArray {
        val channels = frame.channelCount.coerceAtLeast(1)
        val frames = frame.pcm.size / channels
        val mono = FloatArray(frames) { i ->
            var sum = 0f
            for (c in 0 until channels) sum += frame.pcm[i * channels + c]
            sum / channels / 32768f
        }
        if (frame.sampleRateHz == SAMPLE_RATE) return mono
        val outSize = (frames.toLong() * SAMPLE_RATE / frame.sampleRateHz).toInt()
        val step = frame.sampleRateHz.toDouble() / SAMPLE_RATE
        return FloatArray(outSize) { i ->
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = (pos - i0).toFloat()
            mono[i0] * (1 - frac) + mono[i1] * frac
        }
    }

    private companion object {
        const val TAG = "OfflineVadTranscriber"

        /** Shared with the SDK's benchmark logging: `adb logcat -s SublyBench`. */
        const val BENCH_TAG = "SublyBench"
        const val SAMPLE_RATE = 16_000
        const val VAD_WINDOW = 512
        const val VAD_THRESHOLD = 0.5f

        /** A pause this long ends a segment: a breath between subtitle lines. */
        const val MIN_SILENCE_S = 0.3f
        const val MIN_SPEECH_S = 0.25f

        /** Continuous speech is cut here: a caption's worth, decoded in < 1 s. */
        const val MAX_SEGMENT_S = 8f

        const val MIN_PARTIAL_INTERVAL_MS = 1_000L

        /** No partial for the first half second: too little to decode usefully. */
        const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE / 2
    }
}
