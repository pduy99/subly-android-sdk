package com.helios.subly.sdk.streaming

import android.util.Log
import com.helios.subly.translator.api.IncrementalTranslator
import com.helios.subly.translator.api.TranslationSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the translation stage shows for the sentence being spoken.
 */
sealed interface TranslationState {
    /** Nothing translated yet in this stream. */
    data object Idle : TranslationState

    /**
     * A sentence in progress.
     *
     * @property text translation so far. The first [stableLength] characters
     *   are completed clause translations and will not change again for this
     *   sentence; only the rest is still being generated. UIs should render
     *   the two separately so settled text never reflows.
     */
    data class Partial(val source: String, val text: String, val stableLength: Int) : TranslationState

    /** A completed sentence and its translation. */
    data class Finalized(val source: String, val text: String) : TranslationState

    /** Translating [source] failed; the stream carries on with the next sentence. */
    data class Failed(val source: String, val error: Throwable) : TranslationState
}

/** One timing measured by the stage, for logs and the benchmark harness. */
data class StageMetric(val kind: Kind, val ms: Long, val sourceChars: Int) {
    enum class Kind {
        /** Piece committed (or sentence finalised) -> first translated token. */
        TTFT,

        /** Piece committed -> its translation complete. */
        PIECE,

        /** ASR final -> Finalized translation published. The end-of-speech latency. */
        FINAL,

        /**
         * Continuous speech hit the wait-k cap and a piece was committed with
         * no clause boundary. [StageMetric.ms] is 0; the count is the signal.
         */
        FORCED_COMMIT,

        /**
         * ASR final -> first token of that sentence's final translation, or
         * to its publication when no generation was left to do. What the
         * reader waits for after the speaker stops.
         */
        FINAL_TTFT,

        /**
         * Queued finals translated as one generation; [StageMetric.ms] is the
         * number merged.
         */
        MERGED_FINALS,
    }
}

/**
 * Translates a stream of ASR hypotheses off the caller's thread.
 *
 * The caller (the ASR collector) only ever calls [onPartial] and [onFinal],
 * which run the [WaitKBuffer] and enqueue work. Neither blocks on the
 * translator, so a multi-second LLM generation can no longer back-pressure
 * the recogniser and, through it, audio capture: the problem that kept
 * LLM translation finals-only before.
 *
 * [run] is the worker: one sentence is one [TranslationSegment], each
 * committed piece one append, streamed token by token into [state]. Work
 * that queues up while a generation runs is merged before the next one
 * starts, so falling behind costs fewer, larger appends rather than an
 * ever-growing backlog: the queue holds at most one pending piece per
 * sentence plus the sentence's final.
 */
class StreamingTranslationStage(
    private val translator: IncrementalTranslator,
    private val policy: WaitKPolicy,
    private val onState: suspend (TranslationState) -> Unit = {},
    private val onMetric: (StageMetric) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private sealed interface Event {
        val sentenceId: Long
        val enqueuedAt: Long
    }

    private data class Commit(
        val delta: String,
        val hypothesis: String,
        override val sentenceId: Long,
        override val enqueuedAt: Long,
    ) : Event

    private data class Final(
        val sentence: String,
        val delta: FinalDelta,
        override val sentenceId: Long,
        override val enqueuedAt: Long,
    ) : Event

    private val buffer = WaitKBuffer(policy)
    private val lock = Any()
    private val queue = ArrayDeque<Event>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private var finished = false

    /** Sentence the recogniser is on. Advances on every [onFinal]. */
    private var inputSentenceId = 0L

    /**
     * Newest sentence the recogniser has produced a hypothesis for. A sentence
     * stays "on screen" until speech for a later one begins, so its final
     * translation still streams live after its ASR final.
     */
    private var liveSentenceId = 0L

    /** Sentence the worker is on, and its translation so far. */
    @Volatile
    private var workerSentenceId = 0L

    @Volatile
    private var workerTranslation = ""

    private var segment: TranslationSegment? = null

    /** A piece of the worker's sentence failed, so its segment is incomplete. */
    private var pieceFailed = false

    private val _state = MutableStateFlow<TranslationState>(TranslationState.Idle)
    val state: StateFlow<TranslationState> = _state.asStateFlow()

    /** How often continuous speech forced a commit with no clause boundary. */
    val forcedCommits: Int get() = synchronized(lock) { buffer.forcedCommits }

    /**
     * Translation available for the sentence currently being spoken, for
     * callers that render a partial caption on every hypothesis. Empty when
     * the worker is still busy with an earlier sentence.
     */
    fun translationForCurrentSentence(): String = synchronized(lock) {
        if (workerSentenceId == inputSentenceId) workerTranslation else ""
    }

    /** Feeds the latest hypothesis of the sentence being spoken. Never blocks. */
    fun onPartial(hypothesis: String) {
        synchronized(lock) {
            if (finished) return
            liveSentenceId = inputSentenceId
            val forcedBefore = buffer.forcedCommits
            val delta = buffer.onPartial(hypothesis) ?: return
            queue.addLast(Commit(delta, hypothesis, inputSentenceId, nanoTime()))
            if (buffer.forcedCommits > forcedBefore) {
                onMetric(StageMetric(StageMetric.Kind.FORCED_COMMIT, 0, delta.length))
            }
        }
        wakeUp.trySend(Unit)
    }

    /** Closes the current sentence with its final text. Never blocks. */
    fun onFinal(sentence: String) {
        if (sentence.isBlank()) return
        synchronized(lock) {
            if (finished) return
            queue.addLast(Final(sentence, buffer.onFinal(sentence), inputSentenceId, nanoTime()))
            inputSentenceId++
        }
        wakeUp.trySend(Unit)
    }

    /** No more input. [run] returns once the queue is drained. */
    fun finish() {
        synchronized(lock) { finished = true }
        wakeUp.trySend(Unit)
    }

    /**
     * The worker loop. Launch it once, on a background dispatcher, in the
     * scope that should own translation. Returns after [finish] once all
     * queued work is done; cancelling it abandons the queue.
     */
    suspend fun run() {
        try {
            while (true) {
                val work = synchronized(lock) { nextWork() }
                if (work == null) {
                    if (synchronized(lock) { finished && queue.isEmpty() }) return
                    wakeUp.receive()
                    continue
                }
                when (work) {
                    is Commit -> translatePiece(work)
                    is Final -> finishSentence(work)
                }
            }
        } finally {
            segment?.close()
            segment = null
        }
    }

    /**
     * Takes the next unit of work, merging what queued up behind it: runs of
     * commits for one sentence collapse into one, and a run of commits
     * followed by that sentence's final collapses into the final.
     */
    private fun nextWork(): Event? {
        val first = queue.removeFirstOrNull() ?: return null
        if (first is Final) return mergeQueuedFinals(first)
        if (first !is Commit) return first

        var merged: Commit = first
        while (true) {
            val next = queue.firstOrNull() ?: return merged
            if (next.sentenceId != merged.sentenceId) return merged
            when (next) {
                is Commit -> {
                    queue.removeFirst()
                    merged = merged.copy(delta = "${merged.delta} ${next.delta}", hypothesis = next.hypothesis)
                }
                is Final -> {
                    queue.removeFirst()
                    val delta = when (val d = next.delta) {
                        is FinalDelta.Continue -> FinalDelta.Continue("${merged.delta} ${d.delta}".trim())
                        FinalDelta.Diverged -> FinalDelta.Diverged
                    }
                    return next.copy(delta = delta, enqueuedAt = next.enqueuedAt).also {
                        pendingTtftStart = merged.enqueuedAt
                    }
                }
            }
        }
    }

    /**
     * Without drafts, finals that queued up behind a running generation are
     * translated as one: each generation pays ~1.5 s before its first token
     * on the measured device, and fast dialogue produced lines faster than
     * that, so latency grew line by line (1.8 s to 13.4 s over a minute).
     * Merging caps the backlog at one generation. Merged sentences become
     * one caption, and the merge stops at [MERGE_MAX_CHARS] so a translation
     * never gets long enough to be slow for its own sake.
     *
     * With drafts on, sentences have pieces in their own segments and are
     * not merged.
     */
    private fun mergeQueuedFinals(first: Final): Final {
        if (policy.drafts) return first
        var merged = first
        var count = 1
        while (true) {
            val next = queue.firstOrNull() as? Final ?: break
            val joined = joinSentences(merged.sentence, next.sentence)
            if (joined.length > MERGE_MAX_CHARS) break
            queue.removeFirst()
            merged = Final(joined, FinalDelta.Continue(joined), next.sentenceId, first.enqueuedAt)
            count++
        }
        if (count > 1) onMetric(StageMetric(StageMetric.Kind.MERGED_FINALS, count.toLong(), merged.sentence.length))
        return merged
    }

    /** Joins two sentences, without a space between unspaced-script text. */
    private fun joinSentences(a: String, b: String): String {
        val unspaced = a.isNotEmpty() && b.isNotEmpty() &&
            isUnspaced(a.last()) && isUnspaced(b.first())
        return if (unspaced) a + b else "$a $b"
    }

    private fun isUnspaced(ch: Char): Boolean {
        val script = Character.UnicodeScript.of(ch.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.THAI ||
            ch in "。！？，、；：…"
    }

    /** Earliest enqueue time of commits folded into a final, for its TTFT. */
    private var pendingTtftStart: Long? = null

    private suspend fun translatePiece(commit: Commit) {
        enterSentence(commit.sentenceId)
        try {
            appendStreaming(commit.delta, commit.hypothesis, commit.sentenceId, commit.enqueuedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed piece is not a failed sentence: the final will
            // translate whatever is left (or diverge and redo it all), so drop
            // the segment and carry on.
            Log.w(TAG, "Piece translation failed; the final will retry it", e)
            segment?.close()
            segment = null
            pieceFailed = true
        }
    }

    private suspend fun finishSentence(final: Final) {
        enterSentence(final.sentenceId)
        val ttftStart = pendingTtftStart ?: final.enqueuedAt
        pendingTtftStart = null
        try {
            val delta = final.delta
            var finalTtftReported = false
            val reportFinalTtft = {
                if (!finalTtftReported) {
                    finalTtftReported = true
                    onMetric(StageMetric(StageMetric.Kind.FINAL_TTFT, elapsedMs(final.enqueuedAt), final.sentence.length))
                }
            }
            val hadPieces = segment != null || workerTranslation.isNotEmpty()
            if (delta is FinalDelta.Diverged || pieceFailed || policy.restructureOnFinal && hadPieces) {
                // The recogniser rewrote committed text, the segment was
                // dropped after a failed piece, or the pieces are in source
                // order (SOV) and the target needs the sentence restructured:
                // translate the whole sentence.
                segment?.close()
                segment = null
                workerTranslation = ""
                appendStreaming(final.sentence, final.sentence, final.sentenceId, ttftStart, reportFinalTtft)
            } else if (delta is FinalDelta.Continue && delta.delta.isNotBlank()) {
                appendStreaming(delta.delta, final.sentence, final.sentenceId, ttftStart, reportFinalTtft)
            }
            reportFinalTtft()
            val text = segment?.translation ?: workerTranslation
            onMetric(StageMetric(StageMetric.Kind.FINAL, elapsedMs(final.enqueuedAt), final.sentence.length))
            publish(TranslationState.Finalized(final.sentence, text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(TranslationState.Failed(final.sentence, e))
        } finally {
            segment?.close()
            segment = null
            pieceFailed = false
            synchronized(lock) {
                workerSentenceId = final.sentenceId + 1
                workerTranslation = ""
            }
        }
    }

    private fun enterSentence(sentenceId: Long) {
        if (sentenceId == workerSentenceId) return
        segment?.close()
        segment = null
        pieceFailed = false
        synchronized(lock) {
            workerSentenceId = sentenceId
            workerTranslation = ""
        }
    }

    private suspend fun appendStreaming(
        delta: String,
        source: String,
        sentenceId: Long,
        startedAt: Long,
        onFirstToken: () -> Unit = {},
    ) {
        val seg = segment ?: translator.openSegment().also { segment = it }
        val settled = seg.translation
        var firstToken = true
        seg.append(delta).collect { pieceSoFar ->
            if (firstToken) {
                firstToken = false
                onMetric(StageMetric(StageMetric.Kind.TTFT, elapsedMs(startedAt), delta.length))
                onFirstToken()
            }
            val text = join(settled, pieceSoFar)
            synchronized(lock) { workerTranslation = text }
            // Live partials only while no later sentence has started. A worker
            // that is behind stays quiet until that sentence's final, rather
            // than pulling the live caption back to an older sentence; a
            // sentence still on screen streams its final translation.
            if (sentenceId >= synchronized(lock) { liveSentenceId }) {
                publish(TranslationState.Partial(source, text, stableLength = settled.length))
            }
        }
        synchronized(lock) { workerTranslation = seg.translation }
        onMetric(StageMetric(StageMetric.Kind.PIECE, elapsedMs(startedAt), delta.length))
    }

    private suspend fun publish(state: TranslationState) {
        _state.value = state
        onState(state)
    }

    private fun elapsedMs(since: Long): Long = (nanoTime() - since) / 1_000_000

    private fun join(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a $b"
    }

    private companion object {
        const val TAG = "StreamingTranslation"

        /**
         * Longest merged sentence: about two short subtitle lines in Chinese,
         * a long one in English. Long enough to clear a backlog, short enough
         * that one generation stays a couple of seconds.
         */
        const val MERGE_MAX_CHARS = 60
    }
}
