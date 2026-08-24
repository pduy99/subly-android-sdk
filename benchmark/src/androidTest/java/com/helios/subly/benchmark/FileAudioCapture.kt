package com.helios.subly.benchmark

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.projection.MediaProjection
import com.helios.subly.audio.api.ProjectionlessAudioCapture
import com.helios.subly.core.model.Amplitude
import com.helios.subly.core.model.AudioFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import kotlin.math.abs

/**
 * The benchmark's file-based entry point into the SDK pipeline: decodes an
 * mp4's audio track and emits it exactly as the production capture would —
 * mono 16 kHz [AudioFrame]s in 50 ms chunks, paced at real time (streaming
 * engines and Whisper's silence-based segmenter assume a live rate), followed
 * by [TRAILING_SILENCE_MS] of silence so segmenters flush the last sentence,
 * after which the flow completes.
 *
 * The whole clip is decoded up front (a 2-minute clip is ~4 MB of PCM), which
 * keeps the emission loop trivially simple and the pacing exact.
 */
class FileAudioCapture(private val file: File) : ProjectionlessAudioCapture {

    private val _finished = CompletableDeferred<Unit>()

    /**
     * Completes when the last frame (including trailing silence) has been
     * emitted.
     *
     * The caller cannot infer this from a wall clock: decoding the whole clip
     * happens inside [frames], before pacing begins, so real playback starts
     * some seconds after collection does. Timing the clip from `start()`
     * therefore ends the run early and silently truncates the tail of the
     * transcript — which is exactly the bug this exists to prevent.
     */
    val finished: Deferred<Unit> get() = _finished

    override fun frames(): Flow<AudioFrame> = flow {
        val pcm = resampleLinear(decodeToMono(file), TARGET_SAMPLE_RATE_HZ)
        val startNanos = System.nanoTime()
        var emittedSamples = 0

        suspend fun paceAndEmit(chunk: ShortArray) {
            val dueMs = emittedSamples * 1_000L / TARGET_SAMPLE_RATE_HZ
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            if (dueMs > elapsedMs) delay(dueMs - elapsedMs)
            emit(
                AudioFrame(
                    pcm = chunk,
                    sampleRateHz = TARGET_SAMPLE_RATE_HZ,
                    channelCount = 1,
                    timestampMs = dueMs,
                    maxAbsSample = chunk.maxOfOrNull { abs(it.toInt()) } ?: 0,
                )
            )
            emittedSamples += chunk.size
        }

        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + FRAME_SIZE_SAMPLES, pcm.size)
            paceAndEmit(pcm.copyOfRange(offset, end))
            offset = end
        }
        repeat(TRAILING_SILENCE_MS / FRAME_DURATION_MS) {
            paceAndEmit(ShortArray(FRAME_SIZE_SAMPLES))
        }
        _finished.complete(Unit)
    }.flowOn(Dispatchers.IO)

    /** A file needs no projection token; the parameter is ignored. */
    override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()

    override fun amplitudes(): Flow<Amplitude> = emptyFlow()

    /** Decode is scoped to the collecting coroutine; nothing to release here. */
    override fun stop() = Unit

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /** @return mono PCM at the file's native sample rate, plus that rate. */
    private fun decodeToMono(file: File): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("No audio track in ${file.name}")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drainCodec(codec, extractor)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drainCodec(codec: MediaCodec, extractor: MediaExtractor): Pcm {
        val samples = ArrayList<ShortArray>()
        var sampleRate = 0
        var channels = 1
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(inIndex))
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> if (outIndex >= 0) {
                    val buffer = requireNotNull(codec.getOutputBuffer(outIndex))
                    if (info.size > 0) {
                        val shorts = ShortArray(info.size / 2)
                        buffer.position(info.offset)
                        buffer.asShortBuffer().get(shorts)
                        samples += downmix(shorts, channels)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        check(sampleRate > 0) { "Decoder never reported an output format" }
        val total = samples.sumOf { it.size }
        val mono = ShortArray(total)
        var pos = 0
        samples.forEach { it.copyInto(mono, pos); pos += it.size }
        return Pcm(mono, sampleRate)
    }

    private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val mono = ShortArray(interleaved.size / channels)
        for (i in mono.indices) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resampleLinear(pcm: Pcm, targetRate: Int): ShortArray {
        if (pcm.sampleRateHz == targetRate) return pcm.samples
        val src = pcm.samples
        val outSize = (src.size.toLong() * targetRate / pcm.sampleRateHz).toInt()
        val out = ShortArray(outSize)
        val step = pcm.sampleRateHz.toDouble() / targetRate
        for (i in out.indices) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = pos - i0
            out[i] = ((1 - frac) * src[i0] + frac * src[i1]).toInt().toShort()
        }
        return out
    }

    private class Pcm(val samples: ShortArray, val sampleRateHz: Int)

    companion object {
        /** Mirrors the production capture (AudioRecordDataSource). */
        const val TARGET_SAMPLE_RATE_HZ = 16_000
        const val FRAME_DURATION_MS = 50
        const val FRAME_SIZE_SAMPLES = TARGET_SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000

        /** Emitted after the clip so silence-based segmenters flush. */
        const val TRAILING_SILENCE_MS = 2_000

        private const val CODEC_TIMEOUT_US = 10_000L
    }
}
