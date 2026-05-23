package com.helios.subly.sdk.data.ai

import com.helios.subly.sdk.domain.model.AudioFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioChunkerTest {

    @Test
    fun `emits one window of windowSamples once enough frames accumulate`() = runTest {
        // 100 ms window / 100 ms hop @ 16 kHz mono -> 1_600 samples per window, no overlap.
        val chunker = AudioChunker(windowMs = 100, hopMs = 100)
        val frames = (0 until 4).map { i -> // 4 x 50 ms = 200 ms total
            AudioFrame(
                pcm = ShortArray(800) { Short.MAX_VALUE }, // full-scale
                sampleRateHz = 16_000,
                channelCount = 1,
                timestampMs = i * 50L,
                maxAbsSample = Short.MAX_VALUE.toInt(),
            )
        }

        val windows = chunker.chunk(frames.asFlow()).toList()

        assertEquals(2, windows.size)
        assertEquals(1_600, windows[0].pcm.size)
        // Int16 max -> 1.0f after normalization.
        assertTrue(windows[0].pcm.all { it in 0.999f..1.0f })
        assertEquals(0L, windows[0].startTimestampMs)
        assertEquals(100L, windows[1].startTimestampMs)
    }

    @Test
    fun `stereo input is down-mixed to mono by channel average`() = runTest {
        val chunker = AudioChunker(windowMs = 100, hopMs = 100)
        // Stereo interleaved: L=10_000, R=-10_000 -> mean 0.
        val pcm = ShortArray(3_200) { idx -> if (idx % 2 == 0) 10_000 else -10_000 }
        val frame = AudioFrame(
            pcm = pcm,
            sampleRateHz = 16_000,
            channelCount = 2,
            timestampMs = 0,
            maxAbsSample = 10_000,
        )
        val windows = chunker.chunk(listOf(frame).asFlow()).toList()

        assertEquals(1, windows.size)
        // 3_200 interleaved samples / 2 channels = 1_600 mono samples == window size.
        assertEquals(1_600, windows[0].pcm.size)
        assertTrue(windows[0].pcm.all { kotlin.math.abs(it) < 1e-4 })
    }

    @Test
    fun `partial trailing audio under one window is not emitted`() = runTest {
        val chunker = AudioChunker(windowMs = 100, hopMs = 100)
        val onlyHalf = AudioFrame(
            pcm = ShortArray(800),
            sampleRateHz = 16_000,
            channelCount = 1,
            timestampMs = 0,
            maxAbsSample = 0,
        )
        val windows = chunker.chunk(listOf(onlyHalf).asFlow()).toList()
        assertTrue(windows.isEmpty())
    }
}
