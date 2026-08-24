package com.helios.subly.sdk

import android.media.projection.MediaProjection
import androidx.annotation.VisibleForTesting
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.audio.api.AudioCapture
import com.helios.subly.audio.api.ProjectionlessAudioCapture
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.Caption
import com.helios.subly.core.model.EngineState
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.core.model.SublyError
import com.helios.subly.translator.api.SublyTranslator
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * A stateful captioning session for one language pair.
 *
 * ## Lifecycle
 * ```kotlin
 * val session = subly.createSession(config)
 * session.prepare()                 // optional pre-warm (start() triggers it too)
 * session.start(mediaProjection)    // Preparing → Ready → Running
 * session.stop()                    // back to Ready; models stay warm
 * session.close()                   // releases engines; session is dead
 * ```
 *
 * ## Observing
 * - [state] — lifecycle only (Idle / Preparing / Ready / Running / Error /
 *   Closed). Drive loading spinners, status copy, and error UI from this.
 * - [captions] — the caption stream. Partials may be conflated under
 *   pressure; finals are buffered and never silently dropped while a
 *   collector is active. Late collectors replay the most recent caption.
 *
 * After an [EngineState.Error] during preparation, calling [prepare] (or
 * [start]) again retries. Errors during a running pipeline stop the pipeline
 * and surface on [state]; call [start] again to retry.
 *
 * Construction is cheap and side-effect free — no I/O happens until
 * [prepare] or [start].
 */
class SublySession internal constructor(
    private val config: LanguageConfig,
    private val audioCapture: AudioCapture,
    private val asrEngine: SublyAsr,
    private val translationEngine: SublyTranslator,
    restorePunctuation: Boolean = true,
) : AutoCloseable {

    /** Null when disabled — ASR text then reaches the extractor untouched. */
    private val punctuation: PunctuationRestorer? =
        if (restorePunctuation) PunctuationRestorer(config.source) else null

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _captions = MutableSharedFlow<Caption>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val captions: SharedFlow<Caption> = _captions.asSharedFlow()

    private var prepJob: Job? = null
    private var pipelineJob: Job? = null

    /**
     * Starts (or retries) model preparation. Idempotent: no-ops while
     * preparation is in flight or already complete.
     *
     * @throws IllegalStateException if the session is [EngineState.Closed].
     */
    @Synchronized
    fun prepare() {
        checkNotClosed()
        if (prepJob?.isActive == true) return
        when (_state.value) {
            is EngineState.Ready, is EngineState.Running -> return
            else -> Unit // Idle or Error → (re)run
        }
        prepJob = sessionScope.launch { runPreparation() }
    }

    /**
     * Starts the capture → ASR → translation pipeline. Triggers [prepare] if
     * needed and waits for it; if preparation fails, the failure surfaces on
     * [state] and the pipeline does not start.
     *
     * Idempotent while the pipeline is running.
     *
     * @throws IllegalStateException if the session is [EngineState.Closed].
     */
    @Synchronized
    fun start(mediaProjection: MediaProjection) {
        startWith { audioCapture.frames(mediaProjection) }
    }

    /**
     * Starts the pipeline without a `MediaProjection` token. Only valid when
     * the installed capture is a [ProjectionlessAudioCapture] (file decoders,
     * fakes) — the default system-audio capture is not.
     *
     * Same semantics as [start] otherwise.
     *
     * @throws IllegalStateException if the session is closed, or if the
     *   installed [AudioCapture] requires a projection token.
     */
    @VisibleForTesting
    @Synchronized
    fun start() {
        val capture = audioCapture as? ProjectionlessAudioCapture ?: throw IllegalStateException(
            "The installed AudioCapture requires a MediaProjection — call " +
                "start(mediaProjection), or install a ProjectionlessAudioCapture " +
                "via Subly.Builder.setAudioCapture { ... }."
        )
        startWith { capture.frames() }
    }

    private fun startWith(frames: () -> Flow<AudioFrame>) {
        checkNotClosed()
        if (pipelineJob?.isActive == true) return
        prepare()

        pipelineJob = sessionScope.launch {
            prepJob?.join()
            if (_state.value !is EngineState.Ready) return@launch // error already on [state]

            _state.value = EngineState.Running
            runPipeline(frames())
        }
    }

    /**
     * Stops the pipeline but keeps models warm — [state] returns to
     * [EngineState.Ready] and [start] can be called again without re-preparing.
     */
    @Synchronized
    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
        if (_state.value is EngineState.Running) {
            _state.value = EngineState.Ready
        }
    }

    /**
     * Releases the session's engines and cancels all work. Terminal and
     * idempotent. The session cannot be reused — create a new one via
     * [Subly.createSession].
     */
    @Synchronized
    override fun close() {
        if (_state.value is EngineState.Closed) return
        _state.value = EngineState.Closed
        sessionScope.cancel()
        // This session owns its engines (created per-session by Subly's
        // factories), so releasing here cannot affect other sessions.
        asrEngine.release()
        translationEngine.release()
    }

    // -------------------------------------------------------------------------
    // Preparation
    // -------------------------------------------------------------------------

    private suspend fun runPreparation() {
        var reachedReady = false

        combine(
            asrEngine.prepareModel(config),
            translationEngine.prepareModel(config),
        ) { asr, trans -> asr to trans }
            .transformWhile { pair ->
                emit(pair)
                val (asr, trans) = pair
                val isTerminal =
                    (asr is ModelPrepState.Ready && trans is ModelPrepState.Ready) || (asr is ModelPrepState.Error) || (trans is ModelPrepState.Error)

                !isTerminal
            }
            // Engines must not throw per contract, but a misbehaving engine
            // shouldn't take the session down with an unhandled exception.
            .catch { e ->
                if (e is CancellationException) throw e
                _state.value = EngineState.Error(
                    SublyError.ModelPreparationFailed(component = null, cause = e)
                )
            }
            .collect { (asr, trans) ->
                when {
                    asr is ModelPrepState.Error -> _state.value = EngineState.Error(
                        SublyError.ModelPreparationFailed(SublyError.Component.ASR, asr.cause)
                    )

                    trans is ModelPrepState.Error -> _state.value = EngineState.Error(
                        SublyError.ModelPreparationFailed(
                            SublyError.Component.TRANSLATOR,
                            trans.cause
                        )
                    )

                    asr is ModelPrepState.Ready && trans is ModelPrepState.Ready -> {
                        // ML Kit's first translate() pays one-off lazy-init
                        // costs (~500 ms observed). Absorb it here, behind the
                        // prepare progress, instead of on the first caption.
                        warmUpTranslator()
                        reachedReady = true
                        _state.value = EngineState.Ready
                    }

                    else -> _state.value = EngineState.Preparing(
                        asrState = asr,
                        translatorState = trans,
                        progress = overallProgress(asr, trans),
                    )
                }
            }

        // Defensive: an engine completed its prepare flow without a terminal
        // emission (contract violation). The old behavior here reported
        // Ready, which presented as "ready but captions never appear" —
        // fail loudly instead.
        if (!reachedReady && _state.value !is EngineState.Error && _state.value !is EngineState.Closed) {
            _state.value = EngineState.Error(
                SublyError.ModelPreparationFailed(
                    component = null,
                    cause = IllegalStateException(
                        "An engine's prepareModel flow completed without emitting Ready or Error."
                    ),
                )
            )
        }
    }

    /** Non-fatal: a failed warm-up just means the first caption pays it. */
    private suspend fun warmUpTranslator() {
        val startNanos = System.nanoTime()
        try {
            translationEngine.translate("Hello.")
            BenchLog.metric(
                "translate_warmup ms=${(System.nanoTime() - startNanos) / 1_000_000} ok=true"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BenchLog.metric(
                "translate_warmup ms=${(System.nanoTime() - startNanos) / 1_000_000} ok=false"
            )
            Log.w(TAG, "Translator warm-up failed (non-fatal)", e)
        }
    }

    private fun overallProgress(asr: ModelPrepState, trans: ModelPrepState): Float {
        fun ModelPrepState.fraction(): Float = when (this) {
            ModelPrepState.Checking -> 0f
            is ModelPrepState.Preparing -> progress.coerceIn(0f, 1f)
            ModelPrepState.Ready -> 1f
            is ModelPrepState.Error -> 0f
        }
        return (asr.fraction() + trans.fraction()) / 2f
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    private suspend fun runPipeline(frames: Flow<AudioFrame>) {
        val extractor = SentenceExtractor(Locale.forLanguageTag(config.source))
        val extractorMutex = Mutex()
        resetPartialThrottle()
        lastFinalOriginal = ""

        channelFlow {
            var flushJob: Job? = null

            asrEngine.transcribe(frames)
                .collect { asrResult ->
                    when (asrResult) {
                        is AsrResult.Partial -> {
                            // Speaker is mid-utterance: a Final for this
                            // segment is coming, and the pooled remainder may
                            // be the start of the sentence being spoken —
                            // hold the idle flush so cross-segment sentences
                            // aren't split.
                            flushJob?.cancel()
                            val pending = extractorMutex.withLock { extractor.pending() }
                            processPartialResult(pending, asrResult.text)?.let { send(it) }
                        }

                        is AsrResult.Final -> {
                            // Restore before pooling: the extractor splits on
                            // terminators, so punctuating here is what lets it
                            // break at sentences instead of at endpoints.
                            val finalChunk = punctuation?.restore(asrResult.text)
                                ?: asrResult.text.trim()
                            if (finalChunk.isEmpty()) return@collect

                            flushJob?.cancel()
                            resetPartialThrottle()

                            val sentences = extractorMutex.withLock {
                                extractor.append(finalChunk)
                                extractor.extractCompleted()
                            }
                            for (sentence in sentences) {
                                if (isDuplicateFinal(sentence)) continue
                                send(buildFinalCaption(sentence, kind = "final"))
                            }

                            // Idle flush: if no further ASR final arrives to
                            // complete the pooled remainder, it's the best
                            // sentence we'll ever get — emit it rather than
                            // stranding it (whisper finals frequently lack
                            // terminal punctuation).
                            if (extractorMutex.withLock { !extractor.isEmpty }) {
                                flushJob = launch {
                                    delay(IDLE_FLUSH_MS)
                                    val remainder = extractorMutex.withLock { extractor.drain() }
                                    if (remainder.isNotEmpty() && !isDuplicateFinal(remainder)) {
                                        BenchLog.metric("sentence_flush len=${remainder.length}")
                                        send(buildFinalCaption(remainder, kind = "flush"))
                                    }
                                }
                            }
                        }
                    }
                }

            // End of audio. The idle flush above is a 3 s timer, so it only
            // rescues the pooled remainder when the speaker happens to fall
            // silent for 3 s before the stream ends — otherwise the block
            // below returns, the channel closes, and the tail is dropped.
            // With engines that emit no terminators (sherpa, vosk) the pool
            // is non-empty almost every time, so this was silently losing the
            // last caption of most sessions: on a 65 s benchmark clip it cost
            // the final 268 characters, a third of a minute of speech.
            flushJob?.cancel()
            val tail = extractorMutex.withLock { extractor.drain() }
            if (tail.isNotEmpty() && !isDuplicateFinal(tail)) {
                BenchLog.metric("sentence_flush_eos len=${tail.length}")
                send(buildFinalCaption(tail, kind = "flush"))
            }
        }
            .catch { e ->
                if (e is CancellationException) throw e
                val error = e as? SublyError ?: SublyError.PipelineFailed(e)
                _state.value = EngineState.Error(error)
            }
            .collect { caption -> _captions.emit(caption) }
    }

    // ---- Partial handling -------------------------------------------------
    //
    // Partials are throwaway, ever-growing hypotheses. Re-translating the whole
    // growing block on every tick is wasteful AND makes the caption reflow —
    // NMT reorders earlier words as more context arrives, so text the reader
    // already read keeps changing. To avoid that we split each partial into:
    //   - a STABLE prefix: the completed sentences up to the last terminator,
    //     translated once and cached until the prefix itself changes;
    //   - a TAIL: the in-progress clause after the last terminator,
    //     re-translated (throttled) as it grows.
    // The emitted caption is prefixTranslation + tailTranslation, so already-
    // spoken sentences stop shifting under the reader and only the live clause
    // moves. A translation failure degrades to source text, never throws.

    private var partialStablePrefix = ""
    private var partialStablePrefixTranslation = ""
    private var lastPartialTail = ""
    private var lastPartialTailTranslation = ""
    private var lastPartialTranslatedAtNanos = 0L

    private fun resetPartialThrottle() {
        partialStablePrefix = ""
        partialStablePrefixTranslation = ""
        lastPartialTail = ""
        lastPartialTailTranslation = ""
        lastPartialTranslatedAtNanos = 0L
    }

    private suspend fun processPartialResult(
        pendingText: String,
        partialText: String,
    ): Caption? {
        val trimmed = partialText.trim()
        if (trimmed.isEmpty()) return null

        val tentativeSentence = "$pendingText $trimmed".trim()

        // Split at the last sentence terminator: everything before it is
        // settled and won't change; only the tail is still in flux.
        val splitAt = lastTerminatorEnd(tentativeSentence)
        val prefix = tentativeSentence.substring(0, splitAt).trim()
        val tail = tentativeSentence.substring(splitAt).trim()

        // Stable prefix: translate once, re-translate only when it changes
        // (i.e. a new sentence just completed). Cost is O(sentences), not
        // O(partials).
        if (prefix != partialStablePrefix) {
            partialStablePrefixTranslation =
                if (prefix.isEmpty()) "" else translatePartial(prefix)
            partialStablePrefix = prefix
        }

        // Tail: the only part re-translated per tick, and throttled.
        if (tail.isEmpty()) {
            lastPartialTail = ""
            lastPartialTailTranslation = ""
        } else {
            val grewEnough =
                tail.length - lastPartialTail.length >= PARTIAL_RETRANSLATE_MIN_GROWTH
            val agedEnough =
                (System.nanoTime() - lastPartialTranslatedAtNanos) / 1_000_000 >= PARTIAL_RETRANSLATE_MIN_INTERVAL_MS
            val shouldTranslate =
                tail != lastPartialTail &&
                    (lastPartialTailTranslation.isEmpty() || grewEnough || agedEnough)
            if (shouldTranslate) {
                lastPartialTailTranslation = translatePartial(tail)
                lastPartialTail = tail
                lastPartialTranslatedAtNanos = System.nanoTime()
            } else if (tail != lastPartialTail) {
                BenchLog.metric("translate_skip kind=partial reason=throttle src_len=${tail.length}")
            }
        }

        val translation = listOf(partialStablePrefixTranslation, lastPartialTailTranslation)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        return Caption(
            originalText = tentativeSentence,
            translatedText = translation,
            isFinal = false,
            mode = Caption.CaptureMode.AUDIO,
        )
    }

    /** Index just past the last sentence terminator, or 0 if there is none. */
    private fun lastTerminatorEnd(text: String): Int {
        for (i in text.indices.reversed()) {
            if (text[i] in SENTENCE_TERMINATORS) return i + 1
        }
        return 0
    }

    /** Partial translation: degrade to the source text on failure, never throw. */
    private suspend fun translatePartial(text: String): String {
        val startNanos = System.nanoTime()
        return try {
            val out = translationEngine.translate(text)
            logTranslateMetric("partial", text, out, startNanos, ok = true)
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTranslateMetric("partial", text, "", startNanos, ok = false)
            Log.w(TAG, "Partial translation failed; emitting source text", e)
            text
        }
    }

    // ---- Final handling ---------------------------------------------------

    private var lastFinalOriginal = ""

    /**
     * Whisper sometimes emits the same sentence twice (a 2x repetition is
     * below [RepetitionFilter]'s collapse threshold because speakers do
     * legitimately repeat themselves — but two *finalized captions* back to
     * back are never useful). Dropping here also saves the duplicate
     * translation call.
     *
     * Backstop to the [SentenceExtractor] de-overlap: that strips word-level
     * overlap *within* the pool, but whole-sentence repeats that survive
     * (e.g. one window ending a sentence and the next re-decoding it under the
     * audio overlap) are caught here. Matching is normalized (case- and
     * punctuation-insensitive) and also fires when one of the two fully
     * contains the other, so near-duplicates collapse too.
     */
    private fun isDuplicateFinal(sentence: String): Boolean {
        val now = normalizeForDedupe(sentence)
        val prev = normalizeForDedupe(lastFinalOriginal)
        val duplicate = now.isNotEmpty() && prev.isNotEmpty() &&
            (now == prev ||
                (now.length >= DEDUPE_MIN_CONTAIN_LEN && prev.contains(now)) ||
                (prev.length >= DEDUPE_MIN_CONTAIN_LEN && now.contains(prev)))
        if (duplicate) {
            BenchLog.metric("caption_dedupe len=${sentence.length}")
            // Keep the longer/newer form as the reference so a growing repeat
            // doesn't keep matching the shortest seen variant.
            if (now.length >= prev.length) lastFinalOriginal = sentence
            return true
        }
        lastFinalOriginal = sentence
        return false
    }

    /** Lowercase, strip punctuation, collapse whitespace for dedupe compares. */
    private fun normalizeForDedupe(text: String): String =
        text.lowercase(Locale.forLanguageTag(config.source))
            .replace(NON_ALNUM, " ")
            .trim()
            .replace(MULTI_SPACE, " ")

    private suspend fun buildFinalCaption(sentence: String, kind: String): Caption =
        Caption(
            originalText = sentence,
            translatedText = translateFinalOrThrow(sentence, kind),
            isFinal = true,
            mode = Caption.CaptureMode.AUDIO,
        )

    private suspend fun translateFinalOrThrow(text: String, kind: String): String {
        val startNanos = System.nanoTime()
        return try {
            val out = translationEngine.translate(text)
            logTranslateMetric(kind, text, out, startNanos, ok = true)
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logTranslateMetric(kind, text, "", startNanos, ok = false)
            throw SublyError.TranslationFailed(e)
        }
    }

    private fun logTranslateMetric(
        kind: String,
        src: String,
        dst: String,
        startNanos: Long,
        ok: Boolean,
    ) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        BenchLog.metric(
            "translate kind=$kind ms=$ms src_len=${src.length} dst_len=${dst.length} ok=$ok"
        )
        // Content for accuracy evaluation; no-op unless VERBOSE opted in.
        BenchLog.transcript("translate_text kind=$kind src=\"$src\" dst=\"$dst\"")
    }

    private fun checkNotClosed() = check(_state.value !is EngineState.Closed) {
        "This SublySession is closed. Create a new one via Subly.createSession()."
    }

    private companion object {
        const val TAG = "SublySession"

        /**
         * Sentence terminators used to freeze the completed-sentence prefix of
         * a partial (mirrors [SentenceExtractor]'s set, including CJK marks).
         */
        val SENTENCE_TERMINATORS = setOf('.', '!', '?', '…', '。', '！', '？', '．')

        /** Pool remainder is flushed as a final this long after the last ASR final. */
        const val IDLE_FLUSH_MS = 3_000L

        /** Regexes/threshold for normalized near-duplicate final detection. */
        val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        val MULTI_SPACE = Regex("\\s+")

        /**
         * Containment-based dedupe only fires when the contained string is at
         * least this many normalized chars — short fragments ("yes", "okay")
         * are legitimately repeated and substrings of many sentences.
         */
        const val DEDUPE_MIN_CONTAIN_LEN = 12

        /** Re-translate a partial only if it grew by this many chars… */
        const val PARTIAL_RETRANSLATE_MIN_GROWTH = 12

        /** …or this much time passed since the last partial translation. */
        const val PARTIAL_RETRANSLATE_MIN_INTERVAL_MS = 1_200L
    }
}