package com.helios.subly.asr.whisper

import com.helios.subly.core.model.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SpeechSegmenter(
    private val threshold: Int = SPEECH_THRESHOLD,
    private val silenceHangMs: Int = SILENCE_HANG_MS,
    private val maxSegmentMs: Int = MAX_SEGMENT_MS,
    private val minSegmentMs: Int = MIN_SEGMENT_MS,
    private val preRollMs: Int = PRE_ROLL_MS,
    private val framesToOpenSpeech: Int = SPEECH_FRAMES_TO_OPEN,
) {

    init {
        require(threshold in 0..Short.MAX_VALUE.toInt())
        require(silenceHangMs >= 1)
        require(minSegmentMs in 1..maxSegmentMs)
        require(preRollMs in 0..maxSegmentMs)
    }

    /**
     * Segments [frames] by voice activity. Emits one [Window] per detected
     * utterance.
     */
    fun chunk(frames: Flow<AudioFrame>): Flow<Window> = flow {
        val segment = ArrayDeque<Float>()
        val preRoll = ArrayDeque<Float>()
        var preRollCapacity = 0
        var sampleRate = 0
        var channels = 0
        var silentMsAccum = 0
        var consecutiveLoudFrames = 0
        var inSpeech = false
        var segmentStartMs: Long = -1

        frames.collect { frame ->
            if (sampleRate == 0) {
                sampleRate = frame.sampleRateHz
                channels = frame.channelCount
                preRollCapacity = sampleRate * preRollMs / 1_000
            }
            val frameDurationMs = frameDurationMs(frame, channels)

            if (frame.maxAbsSample > threshold) {
                consecutiveLoudFrames++
                if (!inSpeech && consecutiveLoudFrames >= framesToOpenSpeech) {
                    inSpeech = true
                    segmentStartMs = frame.timestampMs - preRollMsBuffered(preRoll, sampleRate)
                    // Drain pre-roll into the segment so the attack isn't clipped.
                    while (preRoll.isNotEmpty()) segment.addLast(preRoll.removeFirst())
                }
                silentMsAccum = 0
            } else {
                consecutiveLoudFrames = 0
                if (inSpeech) silentMsAccum += frameDurationMs
            }

            if (inSpeech) {
                appendAsMono(segment, frame.pcm, channels)
                val segmentMs = segment.size * 1_000 / sampleRate
                val silenceReached = silentMsAccum >= silenceHangMs
                val maxReached = segmentMs >= maxSegmentMs
                if (silenceReached || maxReached) {
                    if (segmentMs >= minSegmentMs) {
                        emit(toWindow(segment, sampleRate, segmentStartMs))
                    }
                    segment.clear()
                    preRoll.clear()
                    silentMsAccum = 0
                    inSpeech = false
                    segmentStartMs = -1
                }
            } else {
                // Keep pre-roll fresh while we wait for speech to start.
                appendAsMono(preRoll, frame.pcm, channels)
                while (preRoll.size > preRollCapacity) preRoll.removeFirst()
            }
        }
    }

    private fun preRollMsBuffered(preRoll: ArrayDeque<Float>, sampleRateHz: Int): Long =
        (preRoll.size.toLong() * 1_000L) / sampleRateHz

    private fun frameDurationMs(frame: AudioFrame, channelCount: Int): Int {
        val mono = if (channelCount <= 1) frame.pcm.size else frame.pcm.size / channelCount
        return mono * 1_000 / frame.sampleRateHz
    }

    private fun toWindow(buffer: ArrayDeque<Float>, sampleRateHz: Int, startMs: Long): Window {
        return Window(
            pcm = buffer.toFloatArray(),
            sampleRateHz = sampleRateHz,
            startTimestampMs = startMs
        )
    }

    private fun appendAsMono(out: ArrayDeque<Float>, pcm: ShortArray, channelCount: Int) {
        if (channelCount <= 1) {
            for (s in pcm) out.addLast(s.toFloat() / Short.MAX_VALUE)
            return
        }
        var i = 0
        while (i + channelCount <= pcm.size) {
            var acc = 0
            for (c in 0 until channelCount) acc += pcm[i + c].toInt()
            out.addLast((acc.toFloat() / channelCount) / Short.MAX_VALUE)
            i += channelCount
        }
    }

    /** PCM window ready for Whisper inference. */
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
        /** Raw int16 amplitude threshold for "speech vs silence". */
        const val SPEECH_THRESHOLD = 500

        /** ms of continuous below-threshold audio that closes a segment. */
        const val SILENCE_HANG_MS = 600

        /** Hard cap on a single segment; force-flush past this. */
        const val MAX_SEGMENT_MS = 5_000

        /** Drop segments shorter than this (likely noise). */
        const val MIN_SEGMENT_MS = 1000

        /** Pre-buffered audio prepended when speech starts (catches attack). */
        const val PRE_ROLL_MS = 100

        /** Consecutive above-threshold frames required to open a segment. */
        const val SPEECH_FRAMES_TO_OPEN = 2
    }
}
