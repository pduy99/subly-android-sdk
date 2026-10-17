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
 * Offline SenseVoice recognition, made live with voice activity detection.
 *
 * SenseVoice decodes a whole utterance at once, which is what makes it
 * accurate, and fast enough (0.13x real time measured on a Galaxy S10) to do
 * so repeatedly. So:
 *  - Silero VAD cuts the audio into speech segments of at most
 *    [MAX_SEGMENT_S] seconds; each finished segment is decoded once and
 *    emitted as an [AsrResult.Final];
 *  - while a segment is still being spoken, the speech so far is re-decoded
 *    about once a second and emitted as an [AsrResult.Partial], backing off
 *    when decoding takes longer, so partials never fall behind the audio.
 *
 * Output carries punctuation and digits (inverse text normalisation on).
 *
 * The sherpa-onnx runtime is compileOnly in this module: the app must ship
 * the sherpa-onnx AAR.
 */
class SenseVoiceTranscriber internal constructor(
    private val context: Context,
    private val modelLoaderFactory: SherpaOnnxModelLoaderFactory,
) : SublyAsr, AccelerationAware {

    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(context, SherpaOnnxModelLoaderFactory.of(modelDownloader))

    /** Each final is a VAD segment: one utterance, ready to caption as is. */
    override val emitsCompleteUtterances: Boolean = true

    @Volatile
    override var accelerationReport: AccelerationReport? = null
        private set

    /** This SenseVoice build does not punctuate; restore 。，？ for Chinese. */
    private val punctuator = ChinesePunctuator(context, modelLoaderFactory)

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var vadModelPath: String? = null
    private var language = ""

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)
        val wanted = senseVoiceLanguage(config.source)
        if (wanted == null) {
            emit(ModelPrepState.Error(IllegalArgumentException(
                "SenseVoice does not support '${config.source}'. Supported: ${supportedLanguages()}."
            )))
            return@flow
        }

        // Loaded already: the language is a decoding option, not a model.
        synchronized(lock) {
            recognizer?.let { live ->
                if (wanted != language) {
                    live.setConfig(recognizerConfig(live.config.modelConfig.senseVoice.model, live.config.modelConfig.tokens, wanted))
                    language = wanted
                }
            }
        }
        if (recognizer != null) {
            emit(ModelPrepState.Ready)
            return@flow
        }

        val loader = modelLoaderFactory.create(context)
        // Model: 0 -> 90%. VAD (0.6 MB): 90 -> 95%.
        loader.provisionWithProgress(SherpaOnnxModel.SENSE_VOICE).collect { emit(ModelPrepState.Preparing(it * 0.9f)) }
        loader.provisionWithProgress(SherpaOnnxModel.SILERO_VAD).collect { emit(ModelPrepState.Preparing(0.9f + it * 0.02f)) }
        punctuator.prepare().collect { emit(ModelPrepState.Preparing(0.92f + it * 0.03f)) }
        if (!loader.isReady(SherpaOnnxModel.SENSE_VOICE) || !loader.isReady(SherpaOnnxModel.SILERO_VAD)) {
            emit(ModelPrepState.Error(IllegalStateException(
                "SenseVoice model download did not complete — check connectivity and retry."
            )))
            return@flow
        }

        val modelDir = loader.downloadTarget(SherpaOnnxModel.SENSE_VOICE)
        val model = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }?.absolutePath
        val tokens = java.io.File(modelDir, "tokens.txt").absolutePath
        val vad = java.io.File(loader.downloadTarget(SherpaOnnxModel.SILERO_VAD), "silero_vad.onnx").absolutePath
        if (model == null) {
            emit(ModelPrepState.Error(IllegalStateException("SenseVoice model file missing in $modelDir")))
            return@flow
        }

        val start = SystemClock.elapsedRealtime()
        val created = runCatching {
            OfflineRecognizer(assetManager = null, config = recognizerConfig(model, tokens, wanted))
        }.getOrElse { error ->
            emit(ModelPrepState.Error(IllegalStateException("SenseVoice init failed", error)))
            return@flow
        }
        val initMs = SystemClock.elapsedRealtime() - start
        synchronized(lock) {
            recognizer = created
            vadModelPath = vad
            language = wanted
        }
        accelerationReport = AccelerationReport(
            component = "asr:sensevoice",
            active = DelegateType.CPU,
            attempts = listOf(BackendAttempt(DelegateType.CPU, succeeded = true, initMs = initMs)),
        )
        Log.i(TAG, "SenseVoice ready in ${initMs}ms, language=$wanted")
        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> {
        val vadPath = vadModelPath
        if (recognizer == null || vadPath == null) {
            return flow {
                throw IllegalStateException("SenseVoice not prepared. Collect prepareModel() to Ready before transcribe().")
            }
        }

        val vad = Vad(
            assetManager = null,
            config = VadModelConfig().apply {
                sileroVadModelConfig.model = vadPath
                sileroVadModelConfig.threshold = VAD_THRESHOLD
                sileroVadModelConfig.minSilenceDuration = MIN_SILENCE_S
                sileroVadModelConfig.minSpeechDuration = MIN_SPEECH_S
                sileroVadModelConfig.maxSpeechDuration = MAX_SEGMENT_S
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
                val text = decode(segment.samples)
                speech = FloatArray(0)
                lastPartial = ""
                // NOTE: never log transcript content — it is end-user speech.
                if (text.isNotBlank()) emit(AsrResult.Final(if (language == "zh" || language == "yue") punctuator.punctuate(text) else text))
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
                        emit(AsrResult.Partial(if (language == "zh" || language == "yue") punctuator.punctuatePartial(text) else text))
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
        }
        punctuator.release()
    }

    override fun supportedLanguages(): List<String> = SherpaOnnxModel.SENSE_VOICE.languages.toList()

    private fun recognizerConfig(model: String, tokens: String, language: String) = OfflineRecognizerConfig().apply {
        modelConfig = OfflineModelConfig().apply {
            senseVoice.model = model
            senseVoice.language = language
            senseVoice.useInverseTextNormalization = true
            this.tokens = tokens
            numThreads = SherpaOnnxModel.SENSE_VOICE.numThreads
        }
    }

    /** SenseVoice's language option for a BCP-47 tag, or null if unsupported. */
    private fun senseVoiceLanguage(tag: String): String? {
        val primary = tag.substringBefore('-').lowercase()
        return when {
            primary == "yue" || tag.lowercase() in setOf("zh-hk", "zh-mo") -> "yue"
            primary in SherpaOnnxModel.SENSE_VOICE.languages -> primary
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
        const val TAG = "SenseVoiceTranscriber"
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
