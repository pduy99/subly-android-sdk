package com.helios.subly.sdk

import android.media.projection.MediaProjection
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.audio.api.ProjectionlessAudioCapture
import com.helios.subly.core.model.Amplitude
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.Caption
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.junit.Test

/**
 * The end of the audio must not eat the end of the transcript.
 *
 * Engines that emit no sentence terminators (sherpa, vosk) leave text pooled
 * in the sentence extractor. Before the end-of-stream drain, that remainder
 * escaped only via a 3 s idle timer — so if the audio ended less than 3 s
 * after the last speech, the final caption was dropped. On a 65 s benchmark
 * clip that silently cost the last 268 characters.
 */
class SessionTailFlushTest {

    private class FakeCapture : ProjectionlessAudioCapture {
        override fun frames(): Flow<AudioFrame> = emptyFlow()
        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
        override fun amplitudes(): Flow<Amplitude> = emptyFlow()
        override fun stop() = Unit
    }

    /** Emits unpunctuated finals — no terminator, so everything pools. */
    private class FakeAsr(private val finals: List<String>) : SublyAsr {
        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> =
            flowOf(*finals.map { AsrResult.Final(it) as AsrResult }.toTypedArray())

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit
        override fun supportedLanguages(): List<String> = listOf("en")
    }

    private class EchoTranslator : SublyTranslator {
        override suspend fun translate(text: String): String = text
        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit
        override fun supportedLanguages(): List<String> = listOf("en", "vi")
    }

    @Test
    fun `pooled text is flushed when the audio ends`() {
        // Two short finals, well under the 28-word cap, with no terminator:
        // nothing can escape the pool except an end-of-stream drain.
        val session = SublySession(
            config = LanguageConfig(source = "en", target = "vi"),
            audioCapture = FakeCapture(),
            asrEngine = FakeAsr(listOf("wine and beer might be had", "in any quantity")),
            translationEngine = EchoTranslator(),
            restorePunctuation = false,
        )

        // The session runs on Dispatchers.Default, so this waits on real time
        // rather than the test scheduler's virtual clock.
        runBlocking {
            val captions = mutableListOf<Caption>()
            val tail = CompletableDeferred<String>()
            val collector = launch(Dispatchers.Default) {
                session.captions.collect {
                    captions += it
                    if (it.isFinal && it.originalText.contains("in any quantity")) {
                        tail.complete(it.originalText)
                    }
                }
            }
            // Let the collector subscribe before the pipeline starts emitting.
            delay(200)

            session.start()
            try {
                // Deliberately shorter than IDLE_FLUSH_MS. The 3 s idle timer
                // is a fallback for a speaker who has paused, not a way to end
                // a stream: when the audio is over the tail must be delivered
                // at once, or the last caption arrives after the session has
                // already been stopped and is never seen.
                withTimeout(IDLE_FLUSH_TIMEOUT_MARGIN_MS) { tail.await() }
            } catch (e: TimeoutCancellationException) {
                val got = captions.filter { it.isFinal }.joinToString(" | ") { it.originalText }
                fail(
                    "end-of-stream text was not flushed within " +
                        "${IDLE_FLUSH_TIMEOUT_MARGIN_MS}ms; final captions were: [$got]"
                )
            } finally {
                collector.cancel()
                session.close()
            }
        }
    }

    private companion object {
        /** Comfortably under the pipeline's 3 s idle flush. */
        const val IDLE_FLUSH_TIMEOUT_MARGIN_MS = 1_500L
    }
}
