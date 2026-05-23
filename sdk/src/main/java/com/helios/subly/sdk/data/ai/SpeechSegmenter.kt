package com.helios.subly.sdk.data.ai

import com.helios.subly.sdk.domain.model.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Voice-activity-detected (VAD) speech segmenter.
 *
 * Replaces the fixed 5 s sliding window with utterance-bounded segments so
 * captions appear within hundreds of milliseconds of the speaker pausing,
 * rather than every [HOP_MS] regardless of speech content.
 *
 * Algorithm (energy-based, runs on the existing per-frame `maxAbsSample`
 * already computed in [com.helios.subly.sdk.data.audio.PcmAmplitude]):
 *
 *  1. Maintain a small **pre-roll** ring buffer (~[PRE_ROLL_MS]) of
 *     pre-speech audio. This is prepended when speech is detected so the
 *     attack of the first syllable isn't clipped.
 *  2. **Enter speech** when [SPEECH_FRAMES_TO_OPEN] consecutive frames have
 *     amplitude > [SPEECH_THRESHOLD]. Append pre-roll + ongoing audio to
 *     the segment buffer.
 *  3. **Exit speech** when [SILENCE_HANG_MS] of continuous low-amplitude
 *     audio follow. Emit the segment if it's at least [MIN_SEGMENT_MS]
 *     (drops short noise spikes).
 *  4. **Force-flush** if a segment exceeds [MAX_SEGMENT_MS] — Whisper
 *     accuracy plateaus around 10–15 s anyway, and unbounded buffering
 *     starves downstream of any output during a continuous monologue.
 *
 * Stereo input is mono-mixed via channel average (same as the previous
 * chunker). Output [Window.startTimestampMs] is the timestamp of the
 * *first sample of speech* (post-pre-roll), so the UI can compute
 * playback→caption latency.
 *
 * @property threshold Speech vs silence amplitude cutoff in raw int16 units
 *   (range 0..32767). 500 ≈ −36 dBFS, which empirically clears YouTube's
 *   playback-capture noise floor while still triggering on quiet dialogue.
 */
internal class SpeechSegmenter(
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
        val out = FloatArray(buffer.size)
        var i = 0
        while (i < out.size) { out[i] = buffer[i]; i++ }
        return Window(pcm = out, sampleRateHz = sampleRateHz, startTimestampMs = startMs)
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
        const val MAX_SEGMENT_MS = 8_000

        /** Drop segments shorter than this (likely noise). */
        const val MIN_SEGMENT_MS = 400

        /** Pre-buffered audio prepended when speech starts (catches attack). */
        const val PRE_ROLL_MS = 200

        /** Consecutive above-threshold frames required to open a segment. */
        const val SPEECH_FRAMES_TO_OPEN = 2
    }
}
