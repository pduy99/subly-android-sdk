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
class SpeechSegmenterTest {

    // 50 ms @ 16 kHz mono = 800 samples per frame.
    private val frameSamples = 800
    private val sampleRate = 16_000

    private fun frame(maxAbs: Int, timestampMs: Long, sampleValue: Short = maxAbs.toShort()) =
        AudioFrame(
            pcm = ShortArray(frameSamples) { sampleValue },
            sampleRateHz = sampleRate,
            channelCount = 1,
            timestampMs = timestampMs,
            maxAbsSample = maxAbs,
        )

    @Test
    fun `emits segment when speech is followed by silence hang`() = runTest {
        val seg = SpeechSegmenter(
            threshold = 500,
            silenceHangMs = 100,
            maxSegmentMs = 5_000,
            minSegmentMs = 50,
            preRollMs = 0,
            framesToOpenSpeech = 1,
        )
        val frames = buildList {
            // 4 frames (200 ms) of speech
            for (i in 0 until 4) add(frame(maxAbs = 5_000, timestampMs = i * 50L))
            // 3 frames (150 ms) of silence -> exceeds hang of 100 ms
            for (i in 4 until 7) add(frame(maxAbs = 50, timestampMs = i * 50L, sampleValue = 50))
        }

        val windows = seg.chunk(frames.asFlow()).toList()
        assertEquals(1, windows.size)
        // 4 speech frames + 2 silence frames (silence hang reached at 2nd) are
        // both retained in the window — trailing silence helps Whisper.
        assertEquals(6 * frameSamples, windows[0].pcm.size)
        assertEquals(0L, windows[0].startTimestampMs)
    }

    @Test
    fun `pure silence emits nothing`() = runTest {
        val seg = SpeechSegmenter(threshold = 500, silenceHangMs = 100)
        val frames = (0 until 20).map { frame(maxAbs = 50, timestampMs = it * 50L, sampleValue = 50) }
        assertTrue(seg.chunk(frames.asFlow()).toList().isEmpty())
    }

    @Test
    fun `force-flushes when segment exceeds maxSegmentMs`() = runTest {
        val seg = SpeechSegmenter(
            threshold = 500,
            silenceHangMs = 500,   // never reached
            maxSegmentMs = 200,    // 4 frames at 50ms
            minSegmentMs = 50,
            preRollMs = 0,
            framesToOpenSpeech = 1,
        )
        val frames = (0 until 8).map { frame(maxAbs = 5_000, timestampMs = it * 50L) }
        val windows = seg.chunk(frames.asFlow()).toList()
        // Two flushes of ~200 ms each.
        assertEquals(2, windows.size)
        assertTrue(windows.all { it.pcm.size <= 4 * frameSamples })
    }

    @Test
    fun `pre-roll prepends quiet audio so attack isn't clipped`() = runTest {
        val seg = SpeechSegmenter(
            threshold = 500,
            silenceHangMs = 100,
            maxSegmentMs = 5_000,
            minSegmentMs = 50,
            preRollMs = 100, // 1_600 samples
            framesToOpenSpeech = 1,
        )
        val frames = buildList {
            // 2 frames (100 ms) of background silence — fills pre-roll
            add(frame(maxAbs = 50, timestampMs = 0L, sampleValue = 50))
            add(frame(maxAbs = 50, timestampMs = 50L, sampleValue = 50))
            // 2 frames of speech
            add(frame(maxAbs = 5_000, timestampMs = 100L))
            add(frame(maxAbs = 5_000, timestampMs = 150L))
            // silence hang
            add(frame(maxAbs = 50, timestampMs = 200L, sampleValue = 50))
            add(frame(maxAbs = 50, timestampMs = 250L, sampleValue = 50))
        }
        val windows = seg.chunk(frames.asFlow()).toList()
        assertEquals(1, windows.size)
        // 2 pre-roll + 2 speech + 2 silence-hang frames = 6 * 800 samples.
        assertEquals(6 * frameSamples, windows[0].pcm.size)
        // Segment timestamp backdated by pre-roll (speech opens at ts=100ms,
        // 100ms of pre-roll buffered -> startTs = 0).
        assertEquals(0L, windows[0].startTimestampMs)
    }

    @Test
    fun `short speech blip below minSegmentMs is dropped`() = runTest {
        val seg = SpeechSegmenter(
            threshold = 500,
            silenceHangMs = 100,
            maxSegmentMs = 5_000,
            minSegmentMs = 300,
            preRollMs = 0,
            framesToOpenSpeech = 1,
        )
        val frames = buildList {
            // 50 ms of speech (well under 300 ms min)
            add(frame(maxAbs = 5_000, timestampMs = 0L))
            for (i in 1 until 6) add(frame(maxAbs = 50, timestampMs = i * 50L, sampleValue = 50))
        }
        assertTrue(seg.chunk(frames.asFlow()).toList().isEmpty())
    }
}
