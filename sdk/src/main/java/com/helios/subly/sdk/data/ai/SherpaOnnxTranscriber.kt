package com.helios.subly.sdk.data.ai

import android.content.Context
import android.util.Log
import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import java.util.concurrent.atomic.AtomicReference

internal class SherpaOnnxTranscriber(
    private val context: Context,
    private val model: SherpaOnnxModel = SherpaOnnxModel.Default,
    private val backend: SherpaOnnxBackend = SherpaOnnxBackend.Jni,
    private val modelLoaderFactory: SherpaOnnxModelLoaderFactory = SherpaOnnxModelLoaderFactory.Default,
) : AiTranscriberRepository {

    /** Lazily-initialized recognizer/stream pair. Cleared on [release]. */
    private val handleRef = AtomicReference<SherpaOnnxBackend.Handle?>(null)

    /**
     * Serializes backend calls. Necessary because sherpa-onnx mutates the
     * stream's internal buffer; concurrent acceptWaveform/decode would
     * corrupt state.
     */
    private val nativeLock = Any()

    override fun transcribeAudio(
        frames: Flow<AudioFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> {
        if (!backend.isAvailable()) {
            Log.d(TAG, "sherpa-onnx backend not available; draining audio.")
            return frames.transform { /* drain, preserve backpressure */ }
        }

        if (ensureHandle() == null) {
            Log.d(TAG, "sherpa-onnx handle not available; draining audio.")
            return frames.transform { /* drain */ }
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
                        emit(
                            TranslationPacket(
                                text = text,
                                sourceLanguageCode = "auto",
                                targetLanguageCode = config.targetLanguageCode,
                                timestampMs = utteranceStartMs,
                                source = TranslationPacket.Source.AUDIO,
                                isFinal = true,
                            ),
                        )
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
                    emit(
                        TranslationPacket(
                            text = text,
                            sourceLanguageCode = "auto",
                            targetLanguageCode = config.targetLanguageCode,
                            timestampMs = utteranceStartMs,
                            source = TranslationPacket.Source.AUDIO,
                            isFinal = false,
                        ),
                    )
                }
            }
            .flowOn(Dispatchers.Default)
    }

    override fun release() {
        synchronized(nativeLock) {
            handleRef.getAndSet(null)?.let { runCatching { backend.release(it) } }
        }
    }

    private fun ensureHandle(): SherpaOnnxBackend.Handle? {
        handleRef.get()?.let { return it }
        val modelDir = modelLoaderFactory.create(context).resolve(model) ?: return null
        val created = backend.init(modelDir, model.sampleRateHz) ?: return null
        return if (handleRef.compareAndSet(null, created)) {
            created
        } else {
            runCatching { backend.release(created) }
            handleRef.get()
        }
    }

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
        const val TAG = "SublySherpaOnnxTx"

        /** Min wall-clock gap between two partial emissions for the same utterance. */
        const val PARTIAL_MIN_INTERVAL_MS = 200L
    }
}
