package com.helios.subly.asr.whisper

import com.helios.subly.core.model.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SpeechSegmenter(
    private val minThreshold: Int = MIN_SPEECH_THRESHOLD,
    private val maxThreshold: Int = MAX_SPEECH_THRESHOLD,
    private val noiseWindowMs: Int = NOISE_WINDOW_MS,
    private val noiseQuantile: Float = NOISE_QUANTILE,
    private val speechMargin: Float = SPEECH_MARGIN,
    private val silenceHangMs: Int = SILENCE_HANG_MS,
    private val maxSegmentMs: Int = MAX_SEGMENT_MS,
    private val minSegmentMs: Int = MIN_SEGMENT_MS,
    private val preRollMs: Int = PRE_ROLL_MS,
    private val framesToOpenSpeech: Int = SPEECH_FRAMES_TO_OPEN,
    private val partialStepMs: Int = PARTIAL_STEP_MS,
    private val maxOverlapMs: Int = MAX_OVERLAP_MS,
) {

    init {
        require(minThreshold in 0..Short.MAX_VALUE.toInt())
        require(maxThreshold in minThreshold..Short.MAX_VALUE.toInt())
        require(noiseWindowMs >= 100)
        require(noiseQuantile in 0f..1f)
        require(speechMargin >= 1f)
        require(silenceHangMs >= 1)
        require(minSegmentMs in 1..maxSegmentMs)
        require(preRollMs in 0..maxSegmentMs)
        require(partialStepMs == 0 || partialStepMs >= 100)
        require(maxOverlapMs in 0 until maxSegmentMs)
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
        var gate: NoiseFloorGate? = null
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
                val frameMs = frameDurationMs(frame, channels).coerceAtLeast(1)
                gate = NoiseFloorGate(
                    slots = (noiseWindowMs / frameMs).coerceAtLeast(1),
                    warmupFrames = (GATE_WARMUP_MS / frameMs).coerceAtLeast(1),
                    quantile = noiseQuantile,
                    margin = speechMargin,
                    minThreshold = minThreshold,
                    maxThreshold = maxThreshold,
                )
                stepSamples = sampleRate.toLong().times(partialStepMs).div(1_000).toInt()
                minPartialSamples = sampleRate.toLong().times(MIN_PARTIAL_MS).div(1_000).toInt()
            }
            val ring = preRoll!!
            val frameDurationMs = frameDurationMs(frame, channels)

            if (gate!!.isSpeech(frame.maxAbsSample)) {
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
                                "partials=$partialsEmitted kept=$kept start_ts=$segmentStartMs " +
                                "thr=${gate!!.threshold} floor=${gate!!.noiseFloor}"
                    )
                    if (kept) {
                        emit(Window(segment.toFloatArray(), sampleRate, segmentStartMs, isFinal = true))
                    }

                    if (maxReached && maxOverlapMs > 0) {
                        // Force-closed mid-utterance at the length cap. Carry the
                        // tail of the audio into the next window as left-context
                        // so the word straddling the cut is decoded whole rather
                        // than split across two windows (which produced boundary
                        // garble like "Abling Hot"/"simple poor"). The duplicated
                        // text the overlap creates is removed downstream by the
                        // SentenceExtractor's de-overlap stitch.
                        val overlapSamples =
                            (sampleRate.toLong() * maxOverlapMs / 1_000).toInt()
                                .coerceIn(0, segment.size)
                        val droppedMs =
                            (segment.size - overlapSamples).toLong() * 1_000 / sampleRate
                        segment.keepLast(overlapSamples)
                        segmentStartMs += droppedMs // carried audio belongs to the next window
                        silentMsAccum = 0
                        samplesAtLastPartial = segment.size // no instant partial for the tail alone
                        partialsEmitted = 0
                        ring.clear()
                        // inSpeech stays true: the utterance continues.
                    } else {
                        segment.clear()
                        ring.clear()
                        silentMsAccum = 0
                        inSpeech = false
                        segmentStartMs = -1
                        samplesAtLastPartial = 0
                        partialsEmitted = 0
                    }
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

        // The capture ended mid-utterance — no silence hang will ever arrive
        // to close the segment. Without this flush the buffered audio is
        // dropped and the last sentence never reaches a caption.
        if (inSpeech) {
            val segmentMs = segment.size.toLong() * 1_000 / sampleRate
            BenchLog.metric(
                "segment close reason=eos segment_ms=$segmentMs " +
                        "close_wait_ms=$silentMsAccum partials=$partialsEmitted " +
                        "kept=${segmentMs >= minSegmentMs} start_ts=$segmentStartMs"
            )
            if (segmentMs >= minSegmentMs) {
                emit(Window(segment.toFloatArray(), sampleRate, segmentStartMs, isFinal = true))
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

        /**
         * Drops all but the last [n] samples, compacting them to the front.
         * Used to carry an overlap tail across a max-length segment cut.
         */
        fun keepLast(n: Int) {
            if (n <= 0) {
                size = 0
                return
            }
            if (n >= size) return
            System.arraycopy(data, size - n, data, 0, n)
            size = n
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

    /**
     * Speech/silence decision with a threshold that tracks the room.
     *
     * A fixed absolute threshold cannot serve both a quietly recorded source
     * and a noisy one. At 500 (int16, ~-36 dBFS) a -38 dBFS recording never
     * opens a segment at all, while roughly -24 dBFS of broadband noise
     * holds the gate permanently open and hands Whisper the whole stream,
     * silence included.
     *
     * The floor here is a low quantile of recent frame peaks. Speech sits in
     * the upper tail of that distribution, so it cannot drag the floor up
     * with it, and there are no attack/decay constants to tune.
     *
     * For the first [warmupFrames] the gate sits wide open at
     * [minThreshold], because there is not yet enough history to tell a
     * quiet room from a quiet talker — and of the two ways to be wrong, a
     * false open costs one wasted decode that the downstream Silero VAD
     * rejects, while a false close loses a caption outright. Once warm the
     * measured floor takes over, so a noisy start is paid for once rather
     * than for the length of the window.
     *
     * The window buys that stability with adaptation lag: a sudden rise in
     * room noise is not fully reflected until most of the window has turned
     * over (~[NOISE_WINDOW_MS]).
     */
    internal class NoiseFloorGate(
        slots: Int,
        warmupFrames: Int,
        private val quantile: Float,
        private val margin: Float,
        private val minThreshold: Int,
        private val maxThreshold: Int,
    ) {
        // Capped at the window: a warm-up longer than the history could never
        // complete, and the refresh counter would climb without bound.
        private val warmupFrames = warmupFrames.coerceIn(1, slots)
        private val history = IntArray(slots)
        private val scratch = IntArray(slots)
        private var cursor = 0
        private var filled = 0
        private var framesSinceRefresh = REFRESH_EVERY_FRAMES

        /** Current speech/silence cut-off, in int16 amplitude. */
        var threshold: Int = minThreshold
            private set

        /** Current noise-floor estimate, in int16 amplitude. */
        var noiseFloor: Int = 0
            private set

        fun isSpeech(maxAbsSample: Int): Boolean {
            history[cursor] = maxAbsSample
            cursor = (cursor + 1) % history.size
            if (filled < history.size) filled++
            // Re-sorting every frame would be pure waste: the floor moves on
            // the scale of the window, not of a frame. Estimate over the
            // frames actually seen, not the whole array — a partly-filled
            // window of zeros would otherwise hold the floor at zero for
            // nearly the length of the window.
            if (filled >= warmupFrames && framesSinceRefresh >= REFRESH_EVERY_FRAMES) {
                framesSinceRefresh = 0
                history.copyInto(scratch, endIndex = filled)
                java.util.Arrays.sort(scratch, 0, filled)
                noiseFloor = scratch[((filled - 1) * quantile).toInt()]
                threshold = (noiseFloor * margin).toInt()
                    .coerceIn(minThreshold, maxThreshold)
            } else {
                framesSinceRefresh++
            }
            return maxAbsSample > threshold
        }

        private companion object {
            const val REFRESH_EVERY_FRAMES = 4
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
        /**
         * Lower bound on the adapted threshold, in int16 amplitude. Binds
         * only in a room quieter than the gate could otherwise resolve; it
         * exists so digital silence cannot drive the threshold to zero.
         */
        const val MIN_SPEECH_THRESHOLD = 24

        /**
         * Upper bound on the adapted threshold. Binds only under noise loud
         * enough that captions are hopeless anyway, and stops a sustained
         * roar from locking the gate shut against the speech that follows.
         */
        const val MAX_SPEECH_THRESHOLD = 16_000

        /**
         * Span of frame history the noise floor is estimated over. Longer is
         * a steadier floor and a longer lag when the room changes; 20 s
         * measured best across the benchmark corpus while still tightening
         * under added broadband noise rather than latching open.
         */
        const val NOISE_WINDOW_MS = 20_000

        /**
         * Quantile of recent frame peaks taken as the noise floor. Low
         * enough to land in the dips between syllables rather than on the
         * speech itself, which is what keeps a talker from raising the floor
         * against their own voice.
         */
        const val NOISE_QUANTILE = 0.02f

        /**
         * How long the gate stays wide open before trusting its own floor
         * estimate. Long enough that a first word is never gated away by a
         * floor measured from that same word; short enough that a noisy room
         * is only mis-segmented once.
         */
        const val GATE_WARMUP_MS = 1_000

        /**
         * Speech must exceed the noise floor by this factor (~3.5 dB).
         * Measured across the benchmark corpus: tighter margins start
         * clipping the soft edges of utterances in recordings that carry
         * real room tone, wider ones stop rejecting noise fast enough to be
         * worth the loss.
         */
        const val SPEECH_MARGIN = 1.5f

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

        /**
         * Audio tail (ms) carried into the next window when a segment is
         * force-closed at [MAX_SEGMENT_MS] mid-utterance. Gives the next
         * window left-context so the word straddling the cut decodes whole.
         * 0 disables overlap (hard cut). Must be < [MAX_SEGMENT_MS].
         */
        const val MAX_OVERLAP_MS = 1_000
    }
}
