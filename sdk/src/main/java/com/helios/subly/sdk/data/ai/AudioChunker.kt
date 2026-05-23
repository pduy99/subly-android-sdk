package com.helios.subly.sdk.data.ai

import com.helios.subly.sdk.domain.model.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Folds the engine's ~50 ms PCM frames into Whisper-sized windows.
 *
 * Whisper performs poorly on sub-1s clips and is most accurate around the
 * 5-30s mark - but every additional second adds latency before the first
 * caption is visible. [WINDOW_MS] = 5_000 ms is the chosen knee.
 *
 * [HOP_MS] < [WINDOW_MS] gives a small overlap so word boundaries that fall
 * on a chunk edge get a second chance in the next pass. The overlap is short
 * (500 ms) to keep duplicate caption rate low; the caller is responsible for
 * de-duping identical consecutive emissions.
 *
 * The output is `FloatArray` in [-1, 1] at the source [AudioFrame.sampleRateHz]
 * - whisper expects 16 kHz, which is exactly what `AudioRecordDataSource`
 * already configures, so no resampling is needed today. Stereo (channel 2)
 * is down-mixed by simple averaging.
 */
internal class AudioChunker(
    private val windowMs: Int = WINDOW_MS,
    private val hopMs: Int = HOP_MS,
) {

    init {
        require(hopMs in 1..windowMs) { "hopMs must be in (0, windowMs]" }
    }

    /**
     * Emits one [Window] per hop. Drops the partial trailing window when the
     * upstream completes (Whisper would just hallucinate on it).
     */
    fun chunk(frames: Flow<AudioFrame>): Flow<Window> = flow {
        val buffer = ArrayDeque<Float>()
        var sampleRate = 0
        var channels = 0
        var windowSamples = 0
        var hopSamples = 0
        var windowStartMs: Long = -1

        frames.collect { frame ->
            if (sampleRate == 0) {
                sampleRate = frame.sampleRateHz
                channels = frame.channelCount
                windowSamples = sampleRate * windowMs / 1_000
                hopSamples = sampleRate * hopMs / 1_000
            }
            if (windowStartMs < 0) windowStartMs = frame.timestampMs
            appendAsMono(buffer, frame.pcm, channels)

            while (windowSamples > 0 && buffer.size >= windowSamples) {
                val window = FloatArray(windowSamples)
                var idx = 0
                while (idx < windowSamples) {
                    window[idx] = buffer[idx]
                    idx++
                }
                emit(Window(pcm = window, sampleRateHz = sampleRate, startTimestampMs = windowStartMs))

                // Advance by hopSamples; keep the (windowSamples - hopSamples)
                // tail for the next pass to give an overlap.
                repeat(hopSamples) { if (buffer.isNotEmpty()) buffer.removeFirst() }
                windowStartMs += hopMs
            }
        }
    }

    private fun appendAsMono(out: ArrayDeque<Float>, pcm: ShortArray, channelCount: Int) {
        if (channelCount <= 1) {
            for (s in pcm) out.addLast(s.toFloat() / Short.MAX_VALUE)
            return
        }
        // Interleaved -> mono via channel average.
        var i = 0
        while (i + channelCount <= pcm.size) {
            var acc = 0
            for (c in 0 until channelCount) acc += pcm[i + c].toInt()
            out.addLast((acc.toFloat() / channelCount) / Short.MAX_VALUE)
            i += channelCount
        }
    }

    data class Window(
        val pcm: FloatArray,
        val sampleRateHz: Int,
        val startTimestampMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Window) return false
            return sampleRateHz == other.sampleRateHz &&
                startTimestampMs == other.startTimestampMs &&
                pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var r = pcm.contentHashCode()
            r = 31 * r + sampleRateHz
            r = 31 * r + startTimestampMs.hashCode()
            return r
        }
    }

    companion object {
        const val WINDOW_MS = 5_000
        const val HOP_MS = 4_500
    }
}
