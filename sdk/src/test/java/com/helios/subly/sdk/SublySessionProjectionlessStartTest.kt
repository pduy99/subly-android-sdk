package com.helios.subly.sdk

import android.media.projection.MediaProjection
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.audio.api.AudioCapture
import com.helios.subly.audio.api.ProjectionlessAudioCapture
import com.helios.subly.core.model.Amplitude
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the projection-less [SublySession.start] overload used by capture
 * sources that don't need a `MediaProjection` token (file playback in the
 * accuracy benchmark, fakes in tests).
 */
class SublySessionProjectionlessStartTest {

    @Test
    fun `start without projection runs the pipeline end to end`() = runBlocking {
        val subly = Subly.Builder()
            .setAsrEngine { FakeAsr("Hello world.") }
            .setTranslator { FakeTranslator() }
            .setAudioCapture { FakeFileCapture() }
            .build()

        subly.createSession(LanguageConfig(source = "en", target = "vi")).use { session ->
            session.start()
            val caption = withTimeout(5_000) { session.captions.first { it.isFinal } }

            assertEquals("Hello world.", caption.originalText)
            assertEquals("vi:Hello world.", caption.translatedText)
        }
    }

    @Test
    fun `punctuation restoration turns an unpunctuated final into a caption`() = runBlocking {
        // The end-to-end point of the feature: an engine that emits no
        // terminator still yields a finalized, punctuated caption, because the
        // extractor now has a boundary it recognises.
        val subly = Subly.Builder()
            .setAsrEngine { FakeAsr("good evening and welcome") }
            .setTranslator { FakeTranslator() }
            .setAudioCapture { FakeFileCapture() }
            .setPunctuationRestoration(true)
            .build()

        subly.createSession(LanguageConfig(source = "en", target = "vi")).use { session ->
            session.start()
            val caption = withTimeout(5_000) { session.captions.first { it.isFinal } }
            assertEquals("Good evening and welcome.", caption.originalText)
        }
    }

    @Test
    fun `punctuation restoration can be disabled`() = runBlocking {
        val subly = Subly.Builder()
            .setAsrEngine { FakeAsr("good evening and welcome") }
            .setTranslator { FakeTranslator() }
            .setAudioCapture { FakeFileCapture() }
            .setPunctuationRestoration(false)
            .build()

        subly.createSession(LanguageConfig(source = "en", target = "vi")).use { session ->
            session.start()
            // Without a terminator the extractor cannot complete a sentence,
            // so this caption arrives via the idle flush, unpunctuated.
            val caption = withTimeout(10_000) { session.captions.first { it.isFinal } }
            assertEquals("good evening and welcome", caption.originalText)
        }
    }

    @Test
    fun `start without projection rejects captures that need one`() {
        val subly = Subly.Builder()
            .setAsrEngine { FakeAsr("unused") }
            .setTranslator { FakeTranslator() }
            .setAudioCapture { ProjectionRequiringCapture() }
            .build()

        subly.createSession(LanguageConfig(source = "en", target = "vi")).use { session ->
            assertThrows(IllegalStateException::class.java) { session.start() }
        }
    }

    /** Serves one fixed frame without any projection token, then completes. */
    private class FakeFileCapture : ProjectionlessAudioCapture {
        override fun frames(): Flow<AudioFrame> = flowOf(
            AudioFrame(
                pcm = ShortArray(160),
                sampleRateHz = 16_000,
                channelCount = 1,
                timestampMs = 0L,
                maxAbsSample = 0,
            )
        )

        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> =
            throw AssertionError("Projection-based frames() must not be used by start()")

        override fun amplitudes(): Flow<Amplitude> = emptyFlow()

        override fun stop() = Unit
    }

    /** A plain [AudioCapture] — the overload must refuse to start with it. */
    private class ProjectionRequiringCapture : AudioCapture {
        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = emptyFlow()
        override fun amplitudes(): Flow<Amplitude> = emptyFlow()
        override fun stop() = Unit
    }

    /** Drains the audio, then emits one final utterance. */
    private class FakeAsr(private val finalText: String) : SublyAsr {
        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = flow {
            frames.collect { }
            emit(AsrResult.Final(finalText))
        }

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit

        override fun supportedLanguages(): List<String> = listOf("en")
    }

    private class FakeTranslator : SublyTranslator {
        override suspend fun translate(text: String): String = "vi:$text"

        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> =
            flowOf(ModelPrepState.Ready)

        override fun release() = Unit

        override fun supportedLanguages(): List<String> = listOf("vi")
    }
}
