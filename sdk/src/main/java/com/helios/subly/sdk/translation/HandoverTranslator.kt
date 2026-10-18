package com.helios.subly.sdk.translation

import android.util.Log
import com.helios.subly.core.model.AccelerationAware
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.IncrementalTranslator
import com.helios.subly.translator.api.SublyTranslator
import com.helios.subly.translator.api.TranslationSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * Translates with a fast engine until a better, slower-to-load one is ready.
 *
 * An on-device LLM translates far better than a per-pair NMT model but takes
 * tens of seconds to load (about 23 s for Qwen3-4B on a Galaxy S24 Ultra,
 * almost all of it reading and uploading 2.6 GB of weights), and the process
 * that holds it is the first one Android kills in the background. Making the
 * user wait that long every time they come back is the wrong trade, so:
 *
 *  - [prepareModel] reports [ModelPrepState.Ready] as soon as [fast] is ready
 *    (seconds), and [primary] keeps preparing in the background;
 *  - each sentence is translated by whichever engine was ready when it
 *    started, so the hand-over happens at a sentence boundary, never inside
 *    one; the primary is warmed up with one generation before it takes over;
 *  - if [fast] cannot serve the language pair, preparation waits for
 *    [primary] instead, exactly as it would without this class.
 *
 * [primaryState] exposes the background preparation (download progress on a
 * first run) so an app can keep a foreground service alive while it runs.
 *
 * Owns both engines: [release] releases them.
 */
class HandoverTranslator<P>(
    private val fast: SublyTranslator,
    private val primary: P,
) : SublyTranslator, IncrementalTranslator, AccelerationAware
    where P : SublyTranslator, P : IncrementalTranslator {

    /** Always the stage path: the primary is an LLM, and so the session must be built for one. */
    override val translatesPartials: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var primaryJob: Job? = null

    @Volatile
    private var primaryReady = false

    private val _primaryState = MutableStateFlow<ModelPrepState?>(null)

    /** Background preparation of [primary]; null before the first prepare. */
    val primaryState: StateFlow<ModelPrepState?> = _primaryState.asStateFlow()

    /** Whether translations currently come from [primary]. */
    val isUsingPrimary: Boolean get() = primaryReady

    override val accelerationReport: AccelerationReport?
        get() = if (primaryReady) (primary as? AccelerationAware)?.accelerationReport
        else (fast as? AccelerationAware)?.accelerationReport

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)

        // The fast engine's progress is the session's progress; its error is
        // not (the primary may still serve the pair, see below).
        var fastResult: ModelPrepState? = null
        fast.prepareModel(config)
            .transformWhile { emit(it); it !is ModelPrepState.Ready && it !is ModelPrepState.Error }
            .collect { state ->
                fastResult = state
                if (state is ModelPrepState.Preparing) emit(state)
            }

        if (fastResult is ModelPrepState.Ready) {
            startPrimaryInBackground(config)
            emit(ModelPrepState.Ready)
            return@flow
        }

        // The fast engine cannot serve this pair: wait for the primary, the
        // same experience as having only the primary.
        Log.w(TAG, "Fast translator unavailable ($fastResult); waiting for the primary")
        primaryJob?.cancel()
        primaryReady = false
        primary.prepareModel(config).collect { state ->
            _primaryState.value = state
            if (state is ModelPrepState.Ready) primaryReady = true
            emit(state)
        }
    }

    /**
     * Prepares [primary] for [config] off the caller's flow. A re-prepare (a
     * language switch) restarts it; on a loaded engine that is only a prompt
     * swap, so the primary stays in use.
     */
    private fun startPrimaryInBackground(config: LanguageConfig) {
        primaryJob?.cancel()
        primaryJob = scope.launch {
            try {
                primary.prepareModel(config).collect { state ->
                    _primaryState.value = state
                    if (state is ModelPrepState.Ready && !primaryReady) {
                        // Pay the first generation's one-off cost here, not
                        // on the first caption after the hand-over.
                        runCatching { primary.translate(WARM_UP_TEXT) }
                        primaryReady = true
                        Log.i(TAG, "Primary translator ready; handing over at the next sentence")
                    }
                    if (state is ModelPrepState.Error) {
                        Log.w(TAG, "Primary translator failed to prepare; staying on the fast one", state.cause)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Primary translator preparation threw; staying on the fast one", e)
                _primaryState.value = ModelPrepState.Error(e)
            }
        }
    }

    override suspend fun translate(text: String): String =
        if (primaryReady) primary.translate(text) else fast.translate(text)

    override fun openSegment(): TranslationSegment =
        if (primaryReady) primary.openSegment() else FastSegment()

    /**
     * A sentence translated by [fast]: each piece in one call. Without
     * drafts a sentence is one piece; with drafts the pieces are translated
     * independently, which a per-pair NMT model does anyway.
     */
    private inner class FastSegment : TranslationSegment {
        private val sources = ArrayList<String>()
        private val translations = ArrayList<String>()
        override val source: String get() = sources.joinToString(" ")
        override val translation: String get() = translations.filter { it.isNotEmpty() }.joinToString(" ")

        override fun append(sourceDelta: String): Flow<String> = flow {
            val piece = sourceDelta.trim()
            if (piece.isEmpty()) return@flow
            val out = fast.translate(piece)
            sources += piece
            translations += out
            emit(out)
        }

        override fun close() = Unit
    }

    override fun release() {
        scope.cancel()
        fast.release()
        primary.release()
    }

    /** The primary's languages: the UI should offer what the better engine serves. */
    override fun supportedLanguages(): List<String> = primary.supportedLanguages()

    private companion object {
        const val TAG = "HandoverTranslator"
        const val WARM_UP_TEXT = "Hello."
    }
}
