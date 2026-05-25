package com.helios.subly.sdk.domain.usecase

import com.helios.subly.sdk.domain.model.Amplitude
import com.helios.subly.sdk.domain.repository.MediaPlaybackRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetectSystemSilenceUseCaseTest {

    private val fakeMediaPlaybackRepository = object : MediaPlaybackRepository {
        var isPlaying = true
        override fun isMediaPlaying(): Boolean = isPlaying
    }

    private val useCase = DetectSystemSilenceUseCase(
        mediaPlaybackRepository = fakeMediaPlaybackRepository,
        silenceEpsilon = 32,
        silenceWindowMs = 2_000L,
    )

    @Test
    fun `emits true after sustained silence longer than window`() = runTest {
        val samples = flowOf(
            Amplitude(maxAbsSample = 5, timestampMs = 0),
            Amplitude(maxAbsSample = 10, timestampMs = 1_000),
            Amplitude(maxAbsSample = 4, timestampMs = 2_000),
            Amplitude(maxAbsSample = 0, timestampMs = 2_500),
        )

        val edges = useCase(samples).toList()

        assertEquals(listOf(true), edges)
    }

    @Test
    fun `does not emit true before window elapses`() = runTest {
        val samples = flowOf(
            Amplitude(maxAbsSample = 5, timestampMs = 0),
            Amplitude(maxAbsSample = 10, timestampMs = 500),
            Amplitude(maxAbsSample = 8, timestampMs = 1_500),
        )

        val edges = useCase(samples).toList()

        assertEquals(emptyList<Boolean>(), edges)
    }

    @Test
    fun `loud sample above epsilon never triggers`() = runTest {
        val samples = flowOf(
            Amplitude(maxAbsSample = 5_000, timestampMs = 0),
            Amplitude(maxAbsSample = 8_000, timestampMs = 1_000),
            Amplitude(maxAbsSample = 12_000, timestampMs = 3_000),
        )

        val edges = useCase(samples).toList()

        assertEquals(emptyList<Boolean>(), edges)
    }

    @Test
    fun `dither just above epsilon never triggers`() {
        // PRD A2: threshold must not be literal zero. Samples sit at epsilon+1
        // for far longer than the window; detector must stay silent.
        val noisySamples = flowOf(
            Amplitude(maxAbsSample = 33, timestampMs = 0),
            Amplitude(maxAbsSample = 33, timestampMs = 1_000),
            Amplitude(maxAbsSample = 33, timestampMs = 3_000),
            Amplitude(maxAbsSample = 33, timestampMs = 5_000),
        )

        runTest {
            val edges = useCase(noisySamples).toList()
            assertEquals(emptyList<Boolean>(), edges)
        }
    }

    @Test
    fun `noise after silence flips back to false`() = runTest {
        val samples = flowOf(
            Amplitude(maxAbsSample = 0, timestampMs = 0),
            Amplitude(maxAbsSample = 0, timestampMs = 2_000),       // -> true edge
            Amplitude(maxAbsSample = 8_000, timestampMs = 2_500),   // -> false edge
        )

        val edges = useCase(samples).toList()

        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `silence timer resets after audible interruption`() = runTest {
        val samples = flowOf(
            Amplitude(maxAbsSample = 0, timestampMs = 0),
            Amplitude(maxAbsSample = 0, timestampMs = 1_500),
            // Audible blip resets the silence start time.
            Amplitude(maxAbsSample = 10_000, timestampMs = 1_800),
            Amplitude(maxAbsSample = 0, timestampMs = 2_000),
            Amplitude(maxAbsSample = 0, timestampMs = 3_500),       // only 1.5s of fresh silence
        )

        val edges = useCase(samples).toList()

        assertEquals(emptyList<Boolean>(), edges)
    }

    @Test
    fun `epsilon is inclusive`() = runTest {
        val samples = flowOf(
            Amplitude(maxAbsSample = 32, timestampMs = 0),
            Amplitude(maxAbsSample = 32, timestampMs = 2_000),
        )

        val edges = useCase(samples).toList()

        assertEquals(listOf(true), edges)
    }

    @Test
    fun `does not emit true if media is not playing`() = runTest {
        fakeMediaPlaybackRepository.isPlaying = false
        val samples = flowOf(
            Amplitude(maxAbsSample = 0, timestampMs = 0),
            Amplitude(maxAbsSample = 0, timestampMs = 2_000),
        )

        val edges = useCase(samples).toList()

        assertEquals(emptyList<Boolean>(), edges)
    }

    @Test
    fun `resets timer if media stops playing during silence`() = runTest {
        var callCount = 0
        val mockRepo = object : MediaPlaybackRepository {
            override fun isMediaPlaying(): Boolean {
                callCount++
                // Returns true for the first 2 samples (t=0, t=1000), 
                // but false on the 3rd sample (t=2000), so the timer resets
                return callCount <= 2
            }
        }
        val customUseCase = DetectSystemSilenceUseCase(
            mediaPlaybackRepository = mockRepo,
            silenceEpsilon = 32,
            silenceWindowMs = 2_000L,
        )

        val samples = flowOf(
            Amplitude(maxAbsSample = 0, timestampMs = 0),
            Amplitude(maxAbsSample = 0, timestampMs = 1_000),
            Amplitude(maxAbsSample = 0, timestampMs = 2_000),
            Amplitude(maxAbsSample = 0, timestampMs = 3_000), // media is false, so no emit
        )

        val edges = customUseCase(samples).toList()

        assertEquals(emptyList<Boolean>(), edges)
    }
}
