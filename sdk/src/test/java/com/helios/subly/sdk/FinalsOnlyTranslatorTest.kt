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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A translator that opts out of partials must never be handed one.
 *
 * This is not a nicety. `runPipeline` translates on the same coroutine that
 * collects ASR results, so an engine that takes seconds per call — an
 * on-device LLM — does not merely lag the partial caption when it is asked to
 * translate one every 200 ms: it applies backpressure through the ASR flow
 * all the way back to audio capture.
 */
class FinalsOnlyTranslatorTest {

    private class FakeCapture : ProjectionlessAudioCapture {
        override fun frames(): Flow<AudioFrame> = emptyFlow()
        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
        override fun amplitudes(): Flow<Amplitude> = emptyFlow()
        override fun stop() = Unit
    }

    /** A growing hypothesis, then the sentence that completes it. */
    private class FakeAsr : SublyAsr {
        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = flow {
            emit(AsrResult.Partial("the quick"))
            emit(AsrResult.Partial("the quick brown"))
            emit(AsrResult.Partial("the quick brown fox"))
            emit(AsrResult.Final("the quick brown fox jumps."))
        }

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit
        override fun supportedLanguages(): List<String> = listOf("en")
    }

    private class RecordingTranslator(
        override val translatesPartials: Boolean,
    ) : SublyTranslator {
        val seen = CopyOnWriteArrayList<String>()

        override suspend fun translate(text: String): String {
            seen += text
            return "[$text]"
        }

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit
        override fun supportedLanguages(): List<String> = listOf("en", "vi")
    }

    private fun runSession(translator: RecordingTranslator): List<Caption> {
        val session = SublySession(
            config = LanguageConfig(source = "en", target = "vi"),
            audioCapture = FakeCapture(),
            asrEngine = FakeAsr(),
            translationEngine = translator,
            restorePunctuation = false,
        )

        // The session runs on Dispatchers.Default, so this waits on real time
        // rather than the test scheduler's virtual clock.
        return runBlocking {
            val captions = CopyOnWriteArrayList<Caption>()
            val sawFinal = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                session.captions.collect {
                    captions += it
                    if (it.isFinal) sawFinal.complete(Unit)
                }
            }
            delay(100)
            session.start()
            withTimeout(5_000) { sawFinal.await() }
            collector.cancel()
            session.close()
            captions.toList()
        }
    }

    @Test
    fun `a finals-only translator sees finals but never partials`() {
        val translator = RecordingTranslator(translatesPartials = false)

        val captions = runSession(translator)

        // The warm-up ("Hello.") runs during preparation and is not a caption,
        // so it is excluded before comparing.
        val captionText = translator.seen.filterNot { it == "Hello." }
        assertEquals(listOf("the quick brown fox jumps."), captionText)

        val partials = captions.filterNot { it.isFinal }
        assertTrue("expected partial captions to still stream", partials.isNotEmpty())
        // Partials still reach the overlay — carrying source text, which is
        // exactly the degradation a failed partial translation already gets.
        assertTrue(partials.all { it.translatedText == it.originalText })
    }

    @Test
    fun `the default translator is still asked to translate partials`() {
        val translator = RecordingTranslator(translatesPartials = true)

        val captions = runSession(translator)

        assertTrue(
            "expected partials to reach the translator",
            translator.seen.any { it.startsWith("the quick") && !it.endsWith(".") },
        )
        val partials = captions.filterNot { it.isFinal }
        assertTrue(partials.isNotEmpty())
        assertTrue(partials.all { it.translatedText.startsWith("[") })
    }
}
