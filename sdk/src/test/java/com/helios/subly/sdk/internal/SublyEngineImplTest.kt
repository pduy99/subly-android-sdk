package com.helios.subly.sdk.internal

import android.content.Context
import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.Amplitude
import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.EngineState
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.model.VisionFrame
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import com.helios.subly.sdk.domain.repository.AudioCaptureRepository
import com.helios.subly.sdk.domain.usecase.DetectSystemSilenceUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SublyEngineImplTest {

    // `MediaProjection` / `Context` are final + `Stub!` in unit tests; mock them so the engine has something to pass through.
    private val fakeProjection: MediaProjection = org.mockito.kotlin.mock()
    private val fakeContext: Context = org.mockito.kotlin.mock()

    private class FakeCapture(
        private val synthetic: List<AudioFrame>,
    ) : AudioCaptureRepository {
        val amplitudeRelay = MutableSharedFlow<Amplitude>(replay = 0, extraBufferCapacity = 64)
        var stopped: Boolean = false
            private set

        override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> =
            synthetic.asFlow().map {
                amplitudeRelay.tryEmit(Amplitude(it.maxAbsSample, it.timestampMs))
                it
            }

        override fun amplitudes(): Flow<Amplitude> = amplitudeRelay

        override fun stop() {
            stopped = true
        }
    }

    private class EchoTranscriber : AiTranscriberRepository {
        var released: Boolean = false
            private set

        override fun transcribeAudio(
            frames: Flow<AudioFrame>,
            config: LanguageConfig,
        ): Flow<TranslationPacket> = frames.map {
            TranslationPacket(
                text = "frame@${it.timestampMs}",
                sourceLanguageCode = null,
                targetLanguageCode = config.targetLanguageCode,
                timestampMs = it.timestampMs,
                source = TranslationPacket.Source.AUDIO,
                isFinal = true,
            )
        }

        override fun recognizeVision(
            frames: Flow<VisionFrame>,
            config: LanguageConfig,
        ): Flow<TranslationPacket> = kotlinx.coroutines.flow.emptyFlow()

        override fun release() {
            released = true
        }
    }

    @Test
    fun `audio pipeline forwards packets to shared flow`() = runTest {
        val frames = listOf(
            frame(maxAbs = 5_000, ts = 0),
            frame(maxAbs = 6_000, ts = 50),
            frame(maxAbs = 7_000, ts = 100),
        )
        val capture = FakeCapture(frames)
        val transcriber = EchoTranscriber()
        val engine = SublyEngineImpl(
            appContext = fakeContext,
            audioCapture = capture,
            transcriber = transcriber,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val packets: SharedFlow<TranslationPacket> = engine.startTranslationPipeline(fakeProjection, "es")
        // Subscribe inline before letting the producer run (replay = 0).
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            packets.take(3).toList()
        }
        advanceUntilIdle()
        val received = collected.await()

        assertEquals(listOf("frame@0", "frame@50", "frame@100"), received.map { it.text })
        assertTrue(received.all { it.targetLanguageCode == "es" })
        engine.stopTranslationPipeline()
        assertTrue(capture.stopped)
        assertTrue(transcriber.released)
    }

    @Test
    fun `silence detector flips engine state to DrmBlocked after window`() = runTest {
        // Two silent frames spanning > 2000 ms trigger the DRM-blocked state.
        val frames = listOf(
            frame(maxAbs = 0, ts = 0),
            frame(maxAbs = 0, ts = 2_100),
        )
        val capture = FakeCapture(frames)
        val engine = SublyEngineImpl(
            appContext = fakeContext,
            audioCapture = capture,
            transcriber = EchoTranscriber(),
            silenceDetector = DetectSystemSilenceUseCase(silenceEpsilon = 32, silenceWindowMs = 2_000L),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        engine.startTranslationPipeline(fakeProjection, "fr")
        advanceUntilIdle()

        val drmReached = engine.engineState.first { it is EngineState.DrmBlocked }
        assertEquals(EngineState.DrmBlocked, drmReached)
        engine.stopTranslationPipeline()
    }

    private fun frame(maxAbs: Int, ts: Long): AudioFrame = AudioFrame(
        pcm = ShortArray(0),
        sampleRateHz = 16_000,
        channelCount = 1,
        timestampMs = ts,
        maxAbsSample = maxAbs,
    )
}
