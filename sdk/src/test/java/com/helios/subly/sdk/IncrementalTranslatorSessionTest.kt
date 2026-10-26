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
import com.helios.subly.translator.api.IncrementalTranslator
import com.helios.subly.translator.api.SublyTranslator
import com.helios.subly.translator.api.TranslationSegment
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
 * An incremental (LLM-class) translator is driven by the streaming stage:
 * settled clauses are translated during the sentence, the final reuses them,
 * and a slow generation never stalls ASR collection.
 */
class IncrementalTranslatorSessionTest {

    private class FakeCapture : ProjectionlessAudioCapture {
        override fun frames(): Flow<AudioFrame> = emptyFlow()
        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
        override fun amplitudes(): Flow<Amplitude> = emptyFlow()
        override fun stop() = Unit
    }

    /** Emits a sentence with one mid-sentence pause and records when it finished. */
    private class FakeAsr : SublyAsr {
        val finishedEmitting = CompletableDeferred<Long>()

        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = flow {
            emit(AsrResult.Partial("the man who lives"))
            emit(AsrResult.Partial("the man who lives next"))
            // The speaker pauses mid-sentence: long enough for the committed
            // clause to be translated and shown before the sentence ends.
            delay(1_500)
            emit(AsrResult.Partial("the man who lives next door"))
            emit(AsrResult.Final("the man who lives next door."))
            finishedEmitting.complete(System.nanoTime())
        }

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit
        override fun supportedLanguages(): List<String> = listOf("en")
    }

    /** Uppercases each piece after [generationMs], like a slow LLM would. */
    private class SlowIncrementalTranslator(private val generationMs: Long) :
        SublyTranslator, IncrementalTranslator {
        val appends = CopyOnWriteArrayList<String>()
        override val translatesPartials = false

        override suspend fun translate(text: String): String = text.uppercase()

        override fun openSegment(): TranslationSegment = object : TranslationSegment {
            private val outs = mutableListOf<String>()
            override val source = ""
            override val translation get() = outs.joinToString(" ")
            override fun append(sourceDelta: String): Flow<String> = flow {
                appends += sourceDelta
                delay(generationMs)
                val out = sourceDelta.uppercase()
                outs += out
                emit(out)
            }

            override fun close() = Unit
        }

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit
        override fun supportedLanguages(): List<String> = listOf("en", "fr")
    }

    @Test
    fun `by default a sentence is translated once, when final, off the ASR coroutine`() {
        val asr = FakeAsr()
        val translator = SlowIncrementalTranslator(generationMs = 1_000)
        val session = SublySession(
            initialConfig = LanguageConfig(source = "en", target = "fr"),
            audioCapture = FakeCapture(),
            asrEngine = asr,
            translationEngine = translator,
            restorePunctuation = false,
            livePreview = false,
        )

        val captions = runBlocking {
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
            withTimeout(10_000) { sawFinal.await() }
            collector.cancel()
            session.close()
            captions.toList()
        }

        assertEquals(listOf("the man who lives next door."), translator.appends)
        assertEquals("THE MAN WHO LIVES NEXT DOOR.", captions.last { it.isFinal }.translatedText)
    }

    @Test
    fun `with drafts, clauses are translated incrementally and ASR is never held up`() {
        val asr = FakeAsr()
        val translator = SlowIncrementalTranslator(generationMs = 1_000)
        val session = SublySession(
            initialConfig = LanguageConfig(source = "en", target = "fr"),
            audioCapture = FakeCapture(),
            asrEngine = asr,
            translationEngine = translator,
            restorePunctuation = false,
            translationDrafts = true,
            livePreview = false,
        )

        val captions = runBlocking {
            val captions = CopyOnWriteArrayList<Caption>()
            val sawFinal = CompletableDeferred<Long>()
            val collector = launch(Dispatchers.Default) {
                session.captions.collect {
                    captions += it
                    if (it.isFinal) sawFinal.complete(System.nanoTime())
                }
            }
            delay(100)
            session.start()
            val finalAt = withTimeout(10_000) { sawFinal.await() }
            val asrDoneAt = asr.finishedEmitting.await()
            // ASR emitted everything long before the translator finished its
            // first one-second generation.
            assertTrue(
                "ASR waited on translation",
                (finalAt - asrDoneAt) / 1_000_000 >= 900,
            )
            collector.cancel()
            session.close()
            captions.toList()
        }

        // One commit during the sentence, then only the rest at the final.
        assertEquals(listOf("the man who lives", "next door."), translator.appends)
        val final = captions.last { it.isFinal }
        assertEquals("the man who lives next door.", final.originalText)
        assertEquals("THE MAN WHO LIVES NEXT DOOR.", final.translatedText)
        assertTrue(captions.any { !it.isFinal && it.translatedText == "THE MAN WHO LIVES" })
    }

    @Test
    fun `a live preview translates the sentence while it is spoken`() {
        val asr = FakeAsr()
        val translator = SlowIncrementalTranslator(generationMs = 300)
        val session = SublySession(
            initialConfig = LanguageConfig(source = "en", target = "fr"),
            audioCapture = FakeCapture(),
            asrEngine = asr,
            translationEngine = translator,
            restorePunctuation = false,
        )

        val captions = runBlocking {
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
            withTimeout(10_000) { sawFinal.await() }
            collector.cancel()
            session.close()
            captions.toList()
        }

        // During the pause the sentence so far was translated as a preview.
        assertTrue(captions.any { !it.isFinal && it.translatedText.startsWith("THE MAN WHO LIVES") })
        assertEquals("THE MAN WHO LIVES NEXT DOOR.", captions.last { it.isFinal }.translatedText)
    }

    @Test
    fun `a preview gives way to the final at once`() {
        val asr = object : SublyAsr {
            val finishedEmitting = CompletableDeferred<Long>()
            override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = flow {
                emit(AsrResult.Partial("the man who lives next"))
                delay(200) // a preview is generating now
                emit(AsrResult.Final("the man who lives next door."))
                finishedEmitting.complete(System.nanoTime())
            }
            override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flowOf(ModelPrepState.Ready)
            override fun release() = Unit
            override fun supportedLanguages(): List<String> = listOf("en")
        }
        val translator = SlowIncrementalTranslator(generationMs = 2_000)
        val session = SublySession(
            initialConfig = LanguageConfig(source = "en", target = "fr"),
            audioCapture = FakeCapture(),
            asrEngine = asr,
            translationEngine = translator,
            restorePunctuation = false,
        )

        runBlocking {
            val sawFinal = CompletableDeferred<Long>()
            val collector = launch(Dispatchers.Default) {
                session.captions.collect { if (it.isFinal) sawFinal.complete(System.nanoTime()) }
            }
            delay(100)
            session.start()
            val finalAt = withTimeout(10_000) { sawFinal.await() }
            val asrFinalAt = asr.finishedEmitting.await()
            // One generation, not the preview's remainder and then the final's.
            assertTrue("final waited for the preview", (finalAt - asrFinalAt) / 1_000_000 < 3_000)
            collector.cancel()
            session.close()
        }
        assertEquals("the man who lives next", translator.appends.first())
        assertEquals("the man who lives next door.", translator.appends.last())
    }
}
