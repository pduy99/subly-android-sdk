package com.helios.subly.sdk

import android.media.projection.MediaProjection
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.audio.api.ProjectionlessAudioCapture
import com.helios.subly.core.model.Amplitude
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.EngineState
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Switching languages reuses the session's engines instead of replacing them. */
class SetLanguagesTest {

    private class FakeCapture : ProjectionlessAudioCapture {
        override fun frames(): Flow<AudioFrame> = emptyFlow()
        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
        override fun amplitudes(): Flow<Amplitude> = emptyFlow()
        override fun stop() = Unit
    }

    private class RecordingAsr(private val prepareMs: Long = 0) : SublyAsr {
        val prepared = CopyOnWriteArrayList<LanguageConfig>()
        var releases = 0

        override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> = emptyFlow()
        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
            emit(ModelPrepState.Checking)
            delay(prepareMs)
            prepared += config
            emit(ModelPrepState.Ready)
        }

        override fun release() {
            releases++
        }

        override fun supportedLanguages() = listOf("en", "zh", "ja")
    }

    private class RecordingTranslator : SublyTranslator {
        val prepared = CopyOnWriteArrayList<LanguageConfig>()
        var releases = 0

        override suspend fun translate(text: String) = text
        override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
            prepared += config
            emit(ModelPrepState.Ready)
        }

        override fun release() {
            releases++
        }

        override fun supportedLanguages() = listOf("en", "zh", "ja", "vi")
    }

    private fun session(asr: SublyAsr, translator: SublyTranslator) = SublySession(
        initialConfig = LanguageConfig(source = "en", target = "vi"),
        audioCapture = FakeCapture(),
        asrEngine = asr,
        translationEngine = translator,
        restorePunctuation = false,
    )

    private fun awaitReady(session: SublySession) = runBlocking {
        withTimeout(5_000) { session.state.first { it is EngineState.Ready } }
    }

    @Test
    fun `a new pair re-prepares the same engines without releasing them`() {
        val asr = RecordingAsr()
        val translator = RecordingTranslator()
        val session = session(asr, translator)

        session.prepare()
        awaitReady(session)
        session.setLanguages(LanguageConfig(source = "zh", target = "vi"))
        runBlocking { delay(50) }
        awaitReady(session)

        assertEquals(LanguageConfig("zh", "vi"), session.languages)
        assertEquals(listOf("en", "zh"), asr.prepared.map { it.source })
        assertEquals(listOf("en", "zh"), translator.prepared.map { it.source })
        assertEquals(0, asr.releases)
        assertEquals(0, translator.releases)
        session.close()
    }

    @Test
    fun `the same pair does not prepare again`() {
        val asr = RecordingAsr()
        val translator = RecordingTranslator()
        val session = session(asr, translator)

        session.prepare()
        awaitReady(session)
        session.setLanguages(LanguageConfig(source = "en", target = "vi"))
        awaitReady(session)

        assertEquals(1, asr.prepared.size)
        session.close()
    }

    @Test
    fun `a switch during preparation waits for it, then prepares the new pair`() {
        val asr = RecordingAsr(prepareMs = 300)
        val translator = RecordingTranslator()
        val session = session(asr, translator)

        session.prepare()
        runBlocking { delay(50) } // mid-preparation
        session.setLanguages(LanguageConfig(source = "ja", target = "vi"))
        runBlocking { delay(50) }
        awaitReady(session)

        // The cancelled en preparation never completed; ja did, once.
        assertEquals(listOf("ja"), asr.prepared.map { it.source })
        assertEquals(LanguageConfig("ja", "vi"), translator.prepared.last())
        session.close()
    }
}
