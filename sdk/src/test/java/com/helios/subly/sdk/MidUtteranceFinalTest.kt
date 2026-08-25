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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A final that the recogniser says did not end a sentence must not be
 * punctuated into one.
 *
 * Whisper decodes fixed-length windows and terminates each as though it were
 * complete. When the length cap cuts mid-utterance, restoring a terminator
 * makes sentence assembly split there and capitalise the continuation, which
 * is what produced captions like "and then with clean wet." / "Hands squeeze
 * them into a ball."
 */
class MidUtteranceFinalTest {

    private class FakeCapture : ProjectionlessAudioCapture {
        override fun frames(): Flow<AudioFrame> = emptyFlow()
        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
        override fun amplitudes(): Flow<Amplitude> = emptyFlow()
        override fun stop() = Unit
    }

    private class FakeAsr(private val finals: List<AsrResult.Final>) : SublyAsr {
        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> =
            flowOf(*finals.map { it as AsrResult }.toTypedArray())

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

    private fun captionsFor(finals: List<AsrResult.Final>): List<Caption> = runBlocking {
        val session = SublySession(
            config = LanguageConfig(source = "en", target = "vi"),
            audioCapture = FakeCapture(),
            asrEngine = FakeAsr(finals),
            translationEngine = EchoTranslator(),
            restorePunctuation = true,
        )
        val out = mutableListOf<Caption>()
        val collector = launch(Dispatchers.Default) { session.captions.collect { out += it } }
        delay(200)
        session.start()
        delay(1_500)
        collector.cancel()
        session.close()
        out.filter { it.isFinal }
    }

    @Test
    fun `a cap-cut final is joined to its continuation instead of ending a caption`() {
        val captions = captionsFor(
            listOf(
                AsrResult.Final("swirl the two dry powders together and then with clean wet",
                    endsSentence = false),
                AsrResult.Final("hands squeeze them into a ball"),
            )
        )

        val joined = captions.joinToString(" ") { it.originalText }
        assertTrue(
            "the cap-cut chunk must not be terminated; got: $joined",
            !joined.contains("clean wet."),
        )
        assertTrue(
            "the continuation must not be capitalised; got: $joined",
            !joined.contains("Hands squeeze"),
        )
    }

    @Test
    fun `a continuation is not flushed as its own caption before it arrives`() {
        // Whisper's finals land a median 4.2 s apart on the benchmark device,
        // so a 3 s idle timer emits the first half as a caption of its own.
        // A final that reports endsSentence=false must hold the pool open.
        val slowAsr = object : SublyAsr {
            override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = flow {
                emit(AsrResult.Final("alloys are basically a mixture of two or more metals",
                    endsSentence = false))
                delay(4_500)   // longer than IDLE_FLUSH_MS, shorter than the backstop
                emit(AsrResult.Final("that conduct electricity"))
            }
            override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
                flowOf(ModelPrepState.Ready)
            override fun release() = Unit
            override fun supportedLanguages(): List<String> = listOf("en")
        }

        val captions = runBlocking {
            val session = SublySession(
                config = LanguageConfig(source = "en", target = "vi"),
                audioCapture = FakeCapture(),
                asrEngine = slowAsr,
                translationEngine = EchoTranslator(),
                restorePunctuation = true,
            )
            val out = mutableListOf<Caption>()
            val collector = launch(Dispatchers.Default) { session.captions.collect { out += it } }
            delay(200)
            session.start()
            delay(6_000)
            collector.cancel()
            session.close()
            out.filter { it.isFinal }
        }

        val joined = captions.joinToString(" | ") { it.originalText }
        assertTrue(
            "the halves must arrive as one caption, not two; got: $joined",
            captions.any { it.originalText.contains("metals that conduct electricity") },
        )
    }

    @Test
    fun `a final that does end a sentence is still punctuated`() {
        val captions = captionsFor(
            listOf(AsrResult.Final("in some areas boiling water for a minute is enough"))
        )

        val joined = captions.joinToString(" ") { it.originalText }
        assertTrue("expected a restored terminator; got: $joined", joined.trim().endsWith("."))
        assertTrue("expected a restored capital; got: $joined", joined.trimStart().startsWith("In"))
    }
}
