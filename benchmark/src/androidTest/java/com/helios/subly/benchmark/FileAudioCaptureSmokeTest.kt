package com.helios.subly.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Decoder canary: proves [FileAudioCapture] turns a known mp4 into the frame
 * stream the SDK pipeline expects, without running any ASR. The bundled clip
 * is ~5.56 s of mono 22.05 kHz AAC, so it exercises resampling to 16 kHz.
 */
class FileAudioCaptureSmokeTest {

    @Test
    fun decodesBundledClipIntoPacedSdkFrames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val clip = File.createTempFile("smoke", ".mp4", context.cacheDir).apply {
            outputStream().use { out ->
                context.assets.open("smoke/smoke_test.mp4").use { it.copyTo(out) }
            }
        }

        val startedAt = System.nanoTime()
        val frames = FileAudioCapture(clip).frames().toList()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        clip.delete()

        assertTrue("expected frames, got none", frames.isNotEmpty())
        frames.forEach { frame ->
            assertEquals(16_000, frame.sampleRateHz)
            assertEquals(1, frame.channelCount)
        }

        // ~5.56 s of audio at 16 kHz plus the trailing silence flush.
        val audioSamples = frames.sumOf { it.pcm.size } -
            FileAudioCapture.TRAILING_SILENCE_MS * 16
        val expectedSamples = (5.56 * 16_000).toInt()
        assertTrue(
            "decoded $audioSamples samples, expected ~$expectedSamples",
            abs(audioSamples - expectedSamples) < expectedSamples * 0.05,
        )

        // Real-time pacing: emission takes at least ~90% of clip + silence time.
        val expectedMs = 5_560 + FileAudioCapture.TRAILING_SILENCE_MS
        assertTrue(
            "emitted in ${elapsedMs}ms — not real-time paced (expected ≥ ${expectedMs * 9 / 10}ms)",
            elapsedMs >= expectedMs * 9 / 10,
        )

        // Timestamps are monotonic and roughly 50 ms apart.
        frames.zipWithNext().forEach { (a, b) ->
            assertTrue("timestamps not monotonic", b.timestampMs > a.timestampMs)
        }
    }
}
