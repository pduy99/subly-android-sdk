package com.helios.subly.asr.sherpa

import android.content.Context
import android.util.Log
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import java.util.concurrent.atomic.AtomicReference

class SherpaOnnxTranscriber internal constructor(
    private val context: Context,
    private val model: SherpaOnnxModel,
    private val backend: SherpaOnnxBackend,
    private val modelLoaderFactory: SherpaOnnxModelLoaderFactory,
) : SublyAsr {

    /**
     * @param modelDownloader downloader used to fetch model files on first
     *   prepare. Defaults to a plain [OkHttpModelDownloader]; supply your own
     *   (e.g. an OkHttp client with auth headers or certificate pinning, often
     *   provided via Hilt) to control how models are fetched.
     */
    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(
        context = context,
        model = SherpaOnnxModel.Default,
        backend = SherpaOnnxBackend.Jni,
        modelLoaderFactory = SherpaOnnxModelLoaderFactory.of(modelDownloader),
    )

    /**
     * Sherpa is a streaming, endpoint-based engine: each final is a complete
     * utterance/clause finalized on a trailing pause. Stream them one-to-one
     * instead of pooling into sentences (which would batch finals and add
     * latency — the opposite of the streaming experience this engine is for).
     */
    override val emitsCompleteUtterances: Boolean = true

    /** Lazily-initialized recognizer/stream pair. Cleared on [release]. */
    private val handleRef = AtomicReference<SherpaOnnxBackend.Handle?>(null)

    /**
     * Serializes backend calls. Necessary because sherpa-onnx mutates the
     * stream's internal buffer; concurrent acceptWaveform/decode would
     * corrupt state.
     */
    private val nativeLock = Any()

    override fun transcribe(
        frames: Flow<AudioFrame>
    ): Flow<AsrResult> {
        // Per the SublyAsr contract: fail loudly on collection rather than
        // silently draining audio (which presents to the user as "ready but
        // no captions ever appear").
        if (!backend.isAvailable() || handleRef.get() == null) {
            return flow {
                throw IllegalStateException(
                    "Sherpa engine not prepared. Collect prepareModel() to Ready before transcribe()."
                )
            }
        }

        // Per-collection state: the previous emitted text (to dedupe
        // partial spam) and the wall-clock timestamp of the last partial
        // emission (to debounce).
        var lastEmittedText = ""
        var lastPartialEmitMs = 0L
        var utteranceStartMs = -1L
        // Only the first caption of a stream reliably starts a sentence. After
        // that, an endpoint is a breath pause, not a full stop, so we stay
        // lower-case until something actually terminates a sentence.
        var atSentenceStart = true
        // Hypothesis decoded but not yet committed as a final. Audio does not
        // always end on a pause — a clip can be cut mid-sentence, and a user
        // can stop capture mid-word — so without an explicit flush the tail of
        // the stream is silently dropped. It previously survived only by
        // accident, because a hard elapsed-time endpoint cut every utterance
        // short; that rule is gone (it severed words), so flush deliberately.
        var pendingText = ""

        return frames
            .transform { frame ->
                val pcm = toMonoFloatPcm(frame, model.sampleRateHz)
                if (pcm.isEmpty()) return@transform

                if (utteranceStartMs < 0) utteranceStartMs = frame.timestampMs

                val result = synchronized(nativeLock) {
                    val h = handleRef.get() ?: return@synchronized null
                    backend.acceptWaveform(h, pcm, model.sampleRateHz)
                    backend.decode(h)
                }

                if (result == null) return@transform
                // The model's token vocabulary is upper-case, so every
                // hypothesis arrives shouting. Normalise here, before partial
                // debounce and endpoint emission, so partials and finals stay
                // consistent — a caption that flips case as it finalises is
                // worse than either form on its own.
                val text = CasingNormalizer.normalise(result.text.trim(), atSentenceStart)

                pendingText = text

                if (result.isEndpoint) {
                    pendingText = ""
                    if (text.isNotEmpty()) {
                        // Casing is normalised above; punctuation is not.
                        // Sherpa is the low-latency streaming engine, and
                        // restoring terminators at endpoints writes periods
                        // mid-clause whenever a speaker pauses for breath
                        // (measured: readability 45 -> 38 on English), so
                        // finals go out unpunctuated and flow immediately.
                        // NOTE: never log transcript content — it is end-user speech.
                        Log.d(TAG, "Emitted final packet (length=${text.length})")
                        emit(AsrResult.Final(text))
                        // The next caption opens a sentence only if this one
                        // closed one. This engine emits no terminators, so in
                        // practice that is false — but it stays correct if a
                        // punctuating model is swapped in later.
                        atSentenceStart = text.last() in SENTENCE_TERMINATORS
                    }
                    synchronized(nativeLock) { handleRef.get()?.let(backend::reset) }
                    lastEmittedText = ""
                    lastPartialEmitMs = 0L
                    utteranceStartMs = -1L
                    return@transform
                }

                // Partial path: skip if text didn't grow, or if we emitted
                // very recently (debounce). NMT downstream is expensive;
                // 200 ms is the sweet spot for "realtime" feel without
                // burning translate quota on every frame.
                val now = frame.timestampMs
                val grew = text.length > lastEmittedText.length
                val dueByTime = (now - lastPartialEmitMs) >= PARTIAL_MIN_INTERVAL_MS
                if (text.isNotEmpty() && grew && dueByTime) {
                    lastEmittedText = text
                    lastPartialEmitMs = now
                    Log.d(TAG, "Emitted partial packet (length=${text.length})")
                    emit(AsrResult.Partial(text))
                }
            }
            .onCompletion { cause ->
                // Only on a clean end of audio. On cancellation the collector
                // is going away and a late caption would arrive after the
                // caller believed the session was over.
                if (cause == null && pendingText.isNotEmpty()) {
                    Log.d(TAG, "Flushed tail packet (length=${pendingText.length})")
                    emit(AsrResult.Final(pendingText))
                    pendingText = ""
                }
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * Extracts the model from APK assets (if needed) then initializes the
     * native backend. Emits extraction progress [0.0, 0.9] during the copy
     * phase and 1.0 once the native context is ready.
     *
     * Per the [ModelPrepState] contract, every exit path emits a terminal
     * [ModelPrepState.Ready] or [ModelPrepState.Error] — never a silent
     * completion.
     */
    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)

        if (!backend.isAvailable()) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException("sherpa-onnx native backend is not available on this device/ABI.")
                )
            )
            return@flow
        }

        if (handleRef.get() != null) {
            emit(ModelPrepState.Ready)
            return@flow
        }

        val loader = modelLoaderFactory.create(context)

        // ASR provisioning (on-disk / bundled assets / download): 0% -> 90%
        loader.provisionWithProgress(model)
            .onEach { p -> emit(ModelPrepState.Preparing(p * 0.9f)) }
            .collect {}

        // A finished-but-not-ready provision means no source was available.
        // Emit Error rather than completing silently (which the session would
        // misread as a "false Ready").
        if (!loader.isReady(model)) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException(
                        "No model files found for ${model::class.simpleName}. " +
                                "The download failed and no assets were bundled — check connectivity and retry."
                    )
                )
            )
            return@flow
        }

        val modelDir = loader.downloadTarget(model).absolutePath
        emit(ModelPrepState.Preparing(0.95f))

        // ASR native init.
        val created = backend.init(modelDir, model.sampleRateHz)
        if (created == null) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException("sherpa-onnx native init failed for model dir: $modelDir")
                )
            )
            return@flow
        }

        if (!handleRef.compareAndSet(null, created)) {
            runCatching { backend.release(created) }
        }

        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    override fun release() {
        synchronized(nativeLock) {
            handleRef.getAndSet(null)?.let { runCatching { backend.release(it) } }
        }
    }

    override fun supportedLanguages(): List<String> = listOf("en")

    /**
     * Convert an int16 stereo/mono [AudioFrame] to the mono float PCM the
     * sherpa-onnx model expects, with a cheap integer-ratio resample to
     * [targetSampleRateHz] when needed.
     */
    private fun toMonoFloatPcm(frame: AudioFrame, targetSampleRateHz: Int): FloatArray {
        val pcm = frame.pcm
        val channels = frame.channelCount.coerceAtLeast(1)
        val sourceRate = frame.sampleRateHz

        // Step 1: mono-mix to float in [-1, 1].
        val monoLen = pcm.size / channels
        if (monoLen == 0) return FloatArray(0)
        val mono = FloatArray(monoLen)
        if (channels == 1) {
            var i = 0
            while (i < monoLen) {
                mono[i] = pcm[i].toFloat() / Short.MAX_VALUE
                i++
            }
        } else {
            var i = 0
            var j = 0
            while (i + channels <= pcm.size) {
                var acc = 0
                for (c in 0 until channels) acc += pcm[i + c].toInt()
                mono[j++] = (acc.toFloat() / channels) / Short.MAX_VALUE
                i += channels
            }
        }

        // Step 2: integer-ratio downsample if source > target. For the
        // common 48k -> 16k case this is a stride-3 decimation; aliasing
        // above the Nyquist of 8 kHz isn't critical for speech ASR.
        if (sourceRate == targetSampleRateHz) return mono
        if (sourceRate <= 0 || targetSampleRateHz <= 0) return mono
        val ratio = sourceRate / targetSampleRateHz
        if (ratio <= 1 || sourceRate % targetSampleRateHz != 0) {
            // Non-integer ratio: best effort linear resample.
            return linearResample(mono, sourceRate, targetSampleRateHz)
        }
        val outLen = monoLen / ratio
        val out = FloatArray(outLen)
        var srcIdx = 0
        var dstIdx = 0
        while (dstIdx < outLen) {
            var sum = 0f
            for (i in 0 until ratio) {
                if (srcIdx + i < monoLen) {
                    sum += mono[srcIdx + i]
                }
            }
            out[dstIdx++] = sum / ratio
            srcIdx += ratio
        }
        return out
    }

    private fun linearResample(src: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (src.isEmpty()) return src
        val outLen = (src.size.toLong() * toRate / fromRate).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val step = src.size.toDouble() / outLen
        var pos = 0.0
        var i = 0
        while (i < outLen) {
            val idx = pos.toInt().coerceAtMost(src.size - 1)
            val frac = (pos - idx).toFloat()
            val next = (idx + 1).coerceAtMost(src.size - 1)
            out[i] = src[idx] * (1f - frac) + src[next] * frac
            pos += step
            i++
        }
        return out
    }

    private companion object {
        const val TAG = "SherpaOnnxTranscriber"

        /** Min wall-clock gap between two partial emissions for the same utterance. */
        const val PARTIAL_MIN_INTERVAL_MS = 200L

        /** Terminators across the scripts this SDK captions, incl. full-width. */
        val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '。', '！', '？')
    }
}