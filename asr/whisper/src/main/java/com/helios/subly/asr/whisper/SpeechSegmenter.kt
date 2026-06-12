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
    private val partialStepMs: Int = PARTIAL_STEP_MS,
) {

    init {
        require(threshold in 0..Short.MAX_VALUE.toInt())
        require(silenceHangMs >= 1)
        require(minSegmentMs in 1..maxSegmentMs)
        require(preRollMs in 0..maxSegmentMs)
        require(partialStepMs == 0 || partialStepMs >= 100)
    }

    /**
     * Segments [frames] by voice activity.
     *
     * While an utterance is in progress, emits a growing snapshot of it as a
     * partial [Window] (isFinal=false) every [partialStepMs] of new audio —
     * this is what enables near-real-time captions instead of waiting for
     * the speaker to pause. When the utterance closes (silence hang or max
     * length), emits the full segment as a final [Window] (isFinal=true).
     * Set [partialStepMs] = 0 to disable partials.
     */
    fun chunk(frames: Flow<AudioFrame>): Flow<Window> = flow {
        val segment = FloatSegmentBuffer()
        var preRoll: FloatRingBuffer? = null
        var sampleRate = 0
        var channels = 0
        var silentMsAccum = 0
        var consecutiveLoudFrames = 0
        var inSpeech = false
        var segmentStartMs: Long = -1
        var stepSamples = 0
        var minPartialSamples = 0
        var samplesAtLastPartial = 0
        var partialsEmitted = 0

        frames.collect { frame ->
            if (sampleRate == 0) {
                sampleRate = frame.sampleRateHz
                channels = frame.channelCount
                preRoll = FloatRingBuffer(sampleRate * preRollMs / 1_000)
                stepSamples = sampleRate.toLong().times(partialStepMs).div(1_000).toInt()
                minPartialSamples = sampleRate.toLong().times(MIN_PARTIAL_MS).div(1_000).toInt()
            }
            val ring = preRoll!!
            val frameDurationMs = frameDurationMs(frame, channels)

            if (frame.maxAbsSample > threshold) {
                consecutiveLoudFrames++
                if (!inSpeech && consecutiveLoudFrames >= framesToOpenSpeech) {
                    inSpeech = true
                    segmentStartMs = frame.timestampMs - ring.size * 1_000L / sampleRate
                    // Drain pre-roll into the segment so the attack isn't clipped.
                    ring.drainTo(segment)
                    samplesAtLastPartial = 0
                }
                silentMsAccum = 0
            } else {
                consecutiveLoudFrames = 0
                if (inSpeech) silentMsAccum += frameDurationMs
            }

            if (inSpeech) {
                segment.appendAsMono(frame.pcm, channels)
                val segmentMs = segment.size.toLong() * 1_000 / sampleRate
                val silenceReached = silentMsAccum >= silenceHangMs
                val maxReached = segmentMs >= maxSegmentMs
                if (silenceReached || maxReached) {
                    val kept = segmentMs >= minSegmentMs
                    // close_wait = dead time between last speech and segment
                    // close. For silence closes this is ~silenceHangMs and is
                    // a floor on final-caption latency — tune SILENCE_HANG_MS
                    // against it.
                    BenchLog.metric(
                        "segment close reason=${if (maxReached) "max" else "silence"} " +
                                "segment_ms=$segmentMs close_wait_ms=$silentMsAccum " +
                                "partials=$partialsEmitted kept=$kept start_ts=$segmentStartMs"
                    )
                    if (kept) {
                        emit(Window(segment.toFloatArray(), sampleRate, segmentStartMs, isFinal = true))
                    }
                    segment.clear()
                    ring.clear()
                    silentMsAccum = 0
                    inSpeech = false
                    segmentStartMs = -1
                    samplesAtLastPartial = 0
                    partialsEmitted = 0
                } else if (
                    stepSamples > 0 &&
                    segment.size >= minPartialSamples &&
                    segment.size - samplesAtLastPartial >= stepSamples
                ) {
                    // In-progress hypothesis; superseded by later partials and
                    // by the final window for this segment.
                    emit(Window(segment.toFloatArray(), sampleRate, segmentStartMs, isFinal = false))
                    samplesAtLastPartial = segment.size
                    partialsEmitted++
                }
            } else {
                // Keep pre-roll fresh while we wait for speech to start.
                ring.appendAsMono(frame.pcm, channels)
            }
        }
    }

    private fun frameDurationMs(frame: AudioFrame, channelCount: Int): Int {
        val mono = if (channelCount <= 1) frame.pcm.size else frame.pcm.size / channelCount
        return mono * 1_000 / frame.sampleRateHz
    }

    /**
     * Growable primitive float buffer. Replaces `ArrayDeque<Float>`, which
     * boxed every sample (~16k `java.lang.Float` allocations/sec at 16 kHz)
     * and caused GC churn exactly when the CPU was needed for inference.
     */
    internal class FloatSegmentBuffer(initialCapacity: Int = 16_384) {
        private var data = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun appendAsMono(pcm: ShortArray, channelCount: Int) {
            if (channelCount <= 1) {
                ensureCapacity(size + pcm.size)
                for (s in pcm) data[size++] = s.toFloat() / Short.MAX_VALUE
                return
            }
            ensureCapacity(size + pcm.size / channelCount)
            var i = 0
            while (i + channelCount <= pcm.size) {
                var acc = 0
                for (c in 0 until channelCount) acc += pcm[i + c].toInt()
                data[size++] = (acc.toFloat() / channelCount) / Short.MAX_VALUE
                i += channelCount
            }
        }

        fun append(sample: Float) {
            ensureCapacity(size + 1)
            data[size++] = sample
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)

        /** Resets length; keeps backing storage to avoid re-allocation. */
        fun clear() {
            size = 0
        }

        private fun ensureCapacity(needed: Int) {
            if (needed <= data.size) return
            var newSize = data.size * 2
            while (newSize < needed) newSize *= 2
            data = data.copyOf(newSize)
        }
    }

    /** Fixed-capacity primitive ring buffer for the pre-roll window. */
    internal class FloatRingBuffer(private val capacity: Int) {
        private val data = FloatArray(maxOf(capacity, 1))
        private var head = 0 // index of oldest sample
        var size: Int = 0
            private set

        fun appendAsMono(pcm: ShortArray, channelCount: Int) {
            if (capacity == 0) return
            if (channelCount <= 1) {
                for (s in pcm) push(s.toFloat() / Short.MAX_VALUE)
                return
            }
            var i = 0
            while (i + channelCount <= pcm.size) {
                var acc = 0
                for (c in 0 until channelCount) acc += pcm[i + c].toInt()
                push((acc.toFloat() / channelCount) / Short.MAX_VALUE)
                i += channelCount
            }
        }

        fun drainTo(out: FloatSegmentBuffer) {
            for (i in 0 until size) out.append(data[(head + i) % data.size])
            clear()
        }

        fun clear() {
            head = 0
            size = 0
        }

        private fun push(sample: Float) {
            if (size < data.size) {
                data[(head + size) % data.size] = sample
                size++
            } else {
                data[head] = sample
                head = (head + 1) % data.size
            }
        }
    }

    /** PCM window ready for Whisper inference. */
    data class Window(
        val pcm: FloatArray,
        val sampleRateHz: Int,
        val startTimestampMs: Long,
        /** True for a closed utterance; false for an in-progress hypothesis. */
        val isFinal: Boolean = true,
        /**
         * [System.nanoTime] at emission. Lets the consumer measure how long
         * a window sat queued before inference (benchmark only; excluded
         * from equality).
         */
        val createdAtNanos: Long = System.nanoTime(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Window) return false
            return sampleRateHz == other.sampleRateHz &&
                    startTimestampMs == other.startTimestampMs &&
                    isFinal == other.isFinal &&
                    pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var r = pcm.contentHashCode()
            r = 31 * r + sampleRateHz
            r = 31 * r + startTimestampMs.hashCode()
            r = 31 * r + isFinal.hashCode()
            return r
        }
    }

    companion object {
        /** Raw int16 amplitude threshold for "speech vs silence". */
        const val SPEECH_THRESHOLD = 500

        /**
         * ms of continuous below-threshold audio that closes a segment.
         * 300 ms (was 600) halves the dead time appended to every utterance;
         * sentence-internal pauses may split segments more often, which is
         * fine for captions since each part is emitted as its own final.
         */
        const val SILENCE_HANG_MS = 300

        /** Hard cap on a single segment; force-flush past this. */
        const val MAX_SEGMENT_MS = 5_000

        /**
         * Drop segments shorter than this (likely noise). 300 ms (was 1000)
         * — at 1000 ms, one-word utterances like "yes"/"stop" were silently
         * discarded.
         */
        const val MIN_SEGMENT_MS = 300

        /** Pre-buffered audio prepended when speech starts (catches attack). */
        const val PRE_ROLL_MS = 100

        /** Consecutive above-threshold frames required to open a segment. */
        const val SPEECH_FRAMES_TO_OPEN = 2

        /** New audio between partial-window emissions. 0 disables partials. */
        const val PARTIAL_STEP_MS = 800

        /**
         * Don't bother emitting partials for segments shorter than this.
         * 1500 (was 500): benchmarks showed sub-second first partials were
         * almost always truncated mid-word and either garbage or full
         * repetition-loop hallucinations — the first useful hypothesis
         * consistently needs ~1.5 s of audio.
         */
        const val MIN_PARTIAL_MS = 1_500
    }
}
