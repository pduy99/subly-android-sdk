package com.helios.subly.asr.vad

import android.content.Context
import android.util.Log
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/**
 * A neural non-speech gate for the streaming ASR engines.
 *
 * Whisper is trained to always emit text, so the whisper engine has always
 * had a Silero gate — but it lives in `whisper-jni.cpp`, which means Sherpa
 * and Vosk have had no gate at all. They will happily decode music, applause
 * or room noise into confident nonsense words. This class puts the same
 * Silero model in front of both, from Kotlin, using the sherpa-onnx runtime
 * the SDK already ships.
 *
 * ## It mutes, it does not drop
 *
 * The obvious design — discard non-speech frames — breaks both engines.
 * Sherpa's endpoint rules and Vosk's Kaldi endpointing both decide where one
 * caption ends by *counting trailing silence*; feed them a stream with the
 * silence cut out and the endpoint never fires. So non-speech is replaced
 * with digital silence instead. Timing is preserved, endpointing still works,
 * and music now reads to the recogniser as exactly what should end a caption.
 *
 * ## Why it looks ahead
 *
 * A gate that decides each window from that window alone cannot help clipping
 * speech onsets, because a decision cannot be applied to audio already
 * emitted. Measured on the accuracy benchmark, that cost the **first word of
 * a caption**: `記事の温度…` came back as `の温度…`, `国会从…` as `不会从…`,
 * `Congress began…` as `Iris began…`. 25 of 64 caption starts changed, every
 * one of them at a caption boundary — which is exactly where the recogniser
 * has just endpointed on a pause, so the gate is closed and the next word is
 * the one that pays.
 *
 * So the gate holds [DEFAULT_LOOKAHEAD_WINDOWS] windows of audio back and
 * mutes a window only when neither it *nor the audio shortly after it* is
 * speech. Onsets survive: by the time a window is emitted, the gate already
 * knows speech was about to start. The cost is a fixed ~128 ms of latency,
 * which is a fifth of the endpoint delay the caption already waits through,
 * and vastly cheaper than losing a word.
 *
 * ## It closes slowly
 *
 * [closeAfterMs] defaults to longer than every rule in
 * `CaptionEndpointTuning` requires, so an ordinary pause between sentences
 * never closes the gate at all. Only sustained non-speech — music, applause,
 * dead air — reaches it, which is the only thing it exists to suppress.
 *
 * ## It fails open
 *
 * Every failure path — no sherpa AAR in the app, wrong ABI, a download that
 * never landed, a native call that threw — leaves [isActive] false and makes
 * [process] the identity function, with no delay and no muting. A broken VAD
 * degrades to today's behaviour; it never silences audio it could not
 * classify.
 *
 * Not thread-safe: one gate per ASR session, driven from that session's
 * single audio thread.
 */
class SpeechGate internal constructor(
    private val loader: VadModelLoader,
    private val backend: VadBackend,
    private val threshold: Float,
    private val closeAfterMs: Int,
    private val lookaheadWindows: Int,
) {

    /**
     * @param downloader used to fetch the ~0.6 MB checkpoint on first
     *   prepare. Defaults to a plain [OkHttpModelDownloader].
     * @param threshold speech probability at or above which the gate opens.
     *   0.5 is Silero's own default and sits far above the noise floor
     *   measured on device (digital silence peaked at 0.043).
     * @param closeAfterMs continuous non-speech required before the gate
     *   mutes. See the class docs for why this is longer than any endpoint
     *   rule's trailing silence.
     * @param lookaheadWindows 32 ms windows of audio held back so a speech
     *   onset can be seen before the audio in front of it is emitted. Zero
     *   disables the delay and reinstates onset clipping; do not.
     */
    @JvmOverloads
    constructor(
        context: Context,
        downloader: ModelDownloader = OkHttpModelDownloader(),
        threshold: Float = DEFAULT_THRESHOLD,
        closeAfterMs: Int = DEFAULT_CLOSE_AFTER_MS,
        lookaheadWindows: Int = DEFAULT_LOOKAHEAD_WINDOWS,
    ) : this(
        DownloadingVadModelLoader(context, downloader),
        VadBackend.Sherpa,
        threshold,
        closeAfterMs,
        lookaheadWindows,
    )

    private var session: VadBackend.Session? = null

    /** Partially-filled analysis window; classified once, then recycled. */
    private val analysis = FloatArray(SileroVadModel.WINDOW_SIZE)
    private var analysisFill = 0

    /**
     * Classified-but-not-yet-emitted windows, and their verdicts.
     *
     * Exactly one of the two sample queues is ever used — a transcriber feeds
     * the gate either shorts or floats for the life of a session, never both.
     * Two typed queues rather than one of `Any` keeps the drain loops free of
     * casts.
     */
    private val pendingShort = ArrayDeque<ShortArray>()
    private val pendingFloat = ArrayDeque<FloatArray>()
    private val pendingOpen = ArrayDeque<Boolean>()

    /** Staging copies of the current window, kept to avoid per-window allocation. */
    private val stagingShort = ShortArray(SileroVadModel.WINDOW_SIZE)
    private val stagingFloat = FloatArray(SileroVadModel.WINDOW_SIZE)

    /**
     * Consecutive sub-threshold windows. The gate is closed once this crosses
     * [closeAfterMs]; it starts at zero so a stream opens audible.
     */
    private var silentWindows = 0

    /** True once the VAD is loaded. While false, [process] is the identity. */
    val isActive: Boolean get() = session != null

    /**
     * Provisions the checkpoint and opens the native VAD, emitting progress
     * in `0f..1f`.
     *
     * Never throws and never fails the caller's preparation: a gate that
     * could not be built is a missing improvement, not a broken session, so
     * the flow completes normally with [isActive] still false. Collect on
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun prepare(): Flow<Float> = flow {
        if (session != null) {
            emit(1f)
            return@flow
        }
        if (!backend.isAvailable()) {
            Log.i(TAG, "No sherpa-onnx runtime in this app; speech gating disabled.")
            emit(1f)
            return@flow
        }

        // `catch` rather than a try/catch around the collect: it intercepts
        // only upstream failures (a dead network, a 404 on the mirror) and
        // leaves downstream ones alone, which is what flow exception
        // transparency requires. A failed download simply leaves the
        // checkpoint missing, and the readiness check below disables the gate.
        loader.provisionWithProgress()
            .catch { error -> Log.w(TAG, "Silero checkpoint download failed: ${error.message}") }
            .collect { emit(it.coerceIn(0f, 1f)) }

        if (!loader.isReady()) {
            Log.w(TAG, "Silero checkpoint unavailable; speech gating disabled.")
            emit(1f)
            return@flow
        }

        session = runCatching { backend.open(loader.modelFile().absolutePath) }
            .getOrElse { error ->
                Log.w(TAG, "Silero VAD init failed; speech gating disabled: ${error.message}")
                null
            }
        // Logged on success as well as failure: every other outcome here is a
        // silent downgrade to pass-through, so "no warning in logcat" is not
        // evidence the gate is running. A benchmark comparing gated against
        // ungated audio needs to be able to tell which one it measured.
        if (session != null) {
            Log.i(
                TAG,
                "Speech gate active (threshold=$threshold closeAfterMs=$closeAfterMs " +
                    "lookaheadWindows=$lookaheadWindows)",
            )
        }
        emit(1f)
    }

    /**
     * Gates mono 16-bit PCM at [SileroVadModel.SAMPLE_RATE_HZ], returning the
     * audio that is ready to go to the recogniser.
     *
     * The result is **not** the same length as the input: output is delayed by
     * [lookaheadWindows] windows and delivered in whole 32 ms windows, so
     * early calls return less than they were given and later calls return
     * more. No audio is lost — call [flush] at end of stream to drain the
     * lookahead. When the gate is inactive the input is returned as-is.
     *
     * The caller's array is never mutated; it may be shared with other
     * consumers (amplitude meters, recording).
     */
    fun process(pcm: ShortArray): ShortArray {
        val vad = session ?: return pcm
        if (pcm.isEmpty()) return pcm

        var offset = 0
        while (offset < pcm.size) {
            val take = min(SileroVadModel.WINDOW_SIZE - analysisFill, pcm.size - offset)
            pcm.copyInto(stagingShort, analysisFill, offset, offset + take)
            for (i in 0 until take) {
                analysis[analysisFill + i] = pcm[offset + i].toFloat() / 32768f
            }
            analysisFill += take
            offset += take
            if (analysisFill == SileroVadModel.WINDOW_SIZE) {
                pendingShort.addLast(stagingShort.copyOf())
                pendingOpen.addLast(decide(vad))
                analysisFill = 0
            }
        }
        return drainShort(keep = lookaheadWindows)
    }

    /**
     * Float overload for engines whose front end is already float PCM in
     * `[-1, 1]` (sherpa-onnx). Same contract as the [ShortArray] version.
     */
    fun process(pcm: FloatArray): FloatArray {
        val vad = session ?: return pcm
        if (pcm.isEmpty()) return pcm

        var offset = 0
        while (offset < pcm.size) {
            val take = min(SileroVadModel.WINDOW_SIZE - analysisFill, pcm.size - offset)
            pcm.copyInto(stagingFloat, analysisFill, offset, offset + take)
            pcm.copyInto(analysis, analysisFill, offset, offset + take)
            analysisFill += take
            offset += take
            if (analysisFill == SileroVadModel.WINDOW_SIZE) {
                pendingFloat.addLast(stagingFloat.copyOf())
                pendingOpen.addLast(decide(vad))
                analysisFill = 0
            }
        }
        return drainFloat(keep = lookaheadWindows)
    }

    /**
     * Releases the held-back lookahead at end of stream.
     *
     * Without this the last [lookaheadWindows] windows — up to ~128 ms — never
     * reach the recogniser, which is the tail of the final utterance.
     */
    fun flushShort(): ShortArray = if (session == null) ShortArray(0) else drainShort(keep = 0)

    /** Float counterpart of [flushShort]. */
    fun flushFloat(): FloatArray = if (session == null) FloatArray(0) else drainFloat(keep = 0)

    /**
     * True when a window should be emitted audible: either it is speech, or
     * speech begins within the lookahead.
     *
     * The second half is the point of the whole delay line. Without it a
     * window that merely *precedes* an onset is muted, and the onset's first
     * phoneme goes with it.
     */
    private fun emitAudible(windowOpen: Boolean): Boolean =
        windowOpen || pendingOpen.take(lookaheadWindows).any { it }

    private fun drainShort(keep: Int): ShortArray {
        val ready = pendingShort.size - keep
        if (ready <= 0) return ShortArray(0)
        val out = ShortArray(ready * SileroVadModel.WINDOW_SIZE)
        for (i in 0 until ready) {
            val window = pendingShort.removeFirst()
            if (emitAudible(pendingOpen.removeFirst())) {
                window.copyInto(out, i * SileroVadModel.WINDOW_SIZE)
            }
        }
        return out
    }

    private fun drainFloat(keep: Int): FloatArray {
        val ready = pendingFloat.size - keep
        if (ready <= 0) return FloatArray(0)
        val out = FloatArray(ready * SileroVadModel.WINDOW_SIZE)
        for (i in 0 until ready) {
            val window = pendingFloat.removeFirst()
            if (emitAudible(pendingOpen.removeFirst())) {
                window.copyInto(out, i * SileroVadModel.WINDOW_SIZE)
            }
        }
        return out
    }

    /** @return whether the gate is open as of this window. */
    private fun decide(vad: VadBackend.Session): Boolean {
        val probability = vad.probability(analysis)
        // Unknown means the model could not judge this window. Passing the
        // audio through is the only safe reading of that.
        if (probability == VadBackend.PROBABILITY_UNKNOWN || probability >= threshold) {
            silentWindows = 0
            return true
        }
        silentWindows++
        return silentWindows * SileroVadModel.WINDOW_MS < closeAfterMs
    }

    /**
     * Clears VAD state, drops any held-back audio, and reopens the gate.
     *
     * Call this at a *stream* boundary — a new capture session — and not on
     * every ASR endpoint. Reopening on each endpoint would be actively
     * harmful: a long passage of music closes the gate, the resulting
     * silence makes the recogniser endpoint, and a reset there would let the
     * music straight back in, so the engine would emit a fresh hallucinated
     * caption every couple of seconds. Silero is built to run continuously
     * across utterances; leaving its state alone is the correct default.
     */
    fun reset() {
        session?.reset()
        pendingShort.clear()
        pendingFloat.clear()
        pendingOpen.clear()
        analysisFill = 0
        silentWindows = 0
    }

    /** Releases the native VAD. Idempotent; the gate becomes a pass-through. */
    fun release() {
        session?.release()
        session = null
        pendingShort.clear()
        pendingFloat.clear()
        pendingOpen.clear()
    }

    companion object {
        private const val TAG = "SpeechGate"

        /** Silero's own default, and far above the measured noise floor. */
        const val DEFAULT_THRESHOLD: Float = 0.5f

        /**
         * Longer than the trailing silence any `CaptionEndpointTuning` rule
         * asks for (the longest is `SILENCE_GUARD` at 2.0 s), so the gate
         * cannot close during the ordinary pause between two sentences. Only
         * sustained non-speech gets muted — which is all it is for.
         */
        const val DEFAULT_CLOSE_AFTER_MS: Int = 2_500

        /**
         * ~128 ms of lookahead. Enough to cover Silero's onset ramp, measured
         * on device at one to two windows (0.04 -> 0.15 -> 0.93 across three
         * consecutive 32 ms windows), with margin for a softer speaker.
         */
        const val DEFAULT_LOOKAHEAD_WINDOWS: Int = 4
    }
}
