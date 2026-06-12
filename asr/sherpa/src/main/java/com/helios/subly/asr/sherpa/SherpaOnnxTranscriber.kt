package com.helios.subly.asr.sherpa

import android.content.Context
import android.util.Log
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import java.util.concurrent.atomic.AtomicReference

class SherpaOnnxTranscriber internal constructor(
    private val context: Context,
    private val model: SherpaOnnxModel,
    private val backend: SherpaOnnxBackend,
    private val modelLoaderFactory: SherpaOnnxModelLoaderFactory,
) : SublyAsr {

    constructor(context: Context) : this(
        context = context,
        model = SherpaOnnxModel.Default,
        backend = SherpaOnnxBackend.Jni,
        modelLoaderFactory = SherpaOnnxModelLoaderFactory.Default,
    )

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
                val text = result.text.trim()

                if (result.isEndpoint) {
                    if (text.isNotEmpty()) {
                        // NOTE: never log transcript content — it is end-user speech.
                        Log.d(TAG, "Emitted final packet (length=${text.length})")
                        emit(AsrResult.Final(text))
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

        // Extraction phase: 0% -> 90%
        loader.extractWithProgress(model)
            .onEach { extractProgress -> emit(ModelPrepState.Preparing(extractProgress * 0.9f)) }
            .collect {}

        // Previously these two failure paths completed the flow silently,
        // which the session interpreted as success ("false Ready").
        if (!loader.isReady(model)) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException(
                        "Model extraction completed but no model files were found for ${model::class.simpleName}. " +
                                "Check that the model assets are bundled or the download finished."
                    )
                )
            )
            return@flow
        }

        val modelDir = loader.downloadTarget(model).absolutePath
        emit(ModelPrepState.Preparing(0.9f))

        // Native init phase: 90% -> 100%
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
    }
}