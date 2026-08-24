package com.helios.subly.asr.whisper

import com.helios.subly.core.model.AudioFrame
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Covers the noise-floor gate. The cases that matter are the two a fixed
 * absolute threshold gets wrong in opposite directions: speech recorded well
 * below the threshold (never opens) and room noise above it (never closes).
 */
class SpeechSegmenterTest {

    // -------------------------------------------------------------------------
    // Frame construction
    // -------------------------------------------------------------------------

    private fun frame(index: Int, samples: ShortArray) = AudioFrame(
        pcm = samples,
        sampleRateHz = SAMPLE_RATE,
        channelCount = 1,
        timestampMs = index.toLong() * FRAME_MS,
        maxAbsSample = samples.maxOfOrNull { abs(it.toInt()) } ?: 0,
    )

    /** A frame of steady tone-like content at [peak]. */
    private fun tone(peak: Int) = ShortArray(FRAME_SAMPLES) { i ->
        (if (i % 8 < 4) peak else -peak).toShort()
    }

    private fun silence() = ShortArray(FRAME_SAMPLES)

    private fun noise(sigma: Int, rng: Random) = ShortArray(FRAME_SAMPLES) {
        (rng.nextInt(-sigma, sigma + 1)).toShort()
    }

    /** Builds a stream from per-frame sample blocks and segments it. */
    private fun segment(
        segmenter: SpeechSegmenter = SpeechSegmenter(partialStepMs = 0),
        blocks: List<ShortArray>,
    ): List<SpeechSegmenter.Window> = runBlocking {
        val frames = blocks.mapIndexed { i, b -> frame(i, b) }
        segmenter.chunk(frames.asFlow()).toList()
    }

    private fun frames(count: Int, block: () -> ShortArray) = List(count) { block() }

    // -------------------------------------------------------------------------
    // Sensitivity
    // -------------------------------------------------------------------------

    @Test
    fun `opens on speech far below the old fixed threshold`() {
        // Peak 150 is well under the 500 the segmenter used to require, and is
        // representative of the quietest clips in the benchmark corpus.
        val windows = segment(blocks =
            frames(20) { silence() } +
                    frames(40) { tone(150) } +
                    frames(20) { silence() }
        )

        assertEquals(1, windows.size)
        assertTrue("expected a final window", windows.single().isFinal)
    }

    @Test
    fun `cold start does not miss the opening word`() {
        // No silence to learn from: the very first frames are speech. The gate
        // starts at its most sensitive precisely so this still opens.
        val windows = segment(blocks =
            frames(30) { tone(200) } + frames(20) { silence() }
        )

        assertEquals(1, windows.size)
    }

    // -------------------------------------------------------------------------
    // Selectivity
    // -------------------------------------------------------------------------

    @Test
    fun `digital silence produces no segments`() {
        assertTrue(segment(blocks = frames(200) { silence() }).isEmpty())
    }

    @Test
    fun `steady noise above the old fixed threshold does not latch the gate open`() {
        // Frame peaks here sit around 900 — comfortably above the old fixed
        // 500, which would have held the gate open for the whole stream and
        // handed Whisper 30 s of noise to decode.
        val rng = Random(7)
        val windows = segment(blocks = frames(600) { noise(900, rng) })

        val covered = windows.sumOf { it.pcm.size }.toLong() * 1_000 / SAMPLE_RATE
        val streamMs = 600L * FRAME_MS
        assertTrue(
            "gate latched open: covered ${covered}ms of ${streamMs}ms",
            covered < streamMs / 4,
        )
    }

    @Test
    fun `speech does not drag the floor up and shut the gate`() {
        // 20 s of unbroken speech must keep producing windows: if the floor
        // tracked the speech it would climb until nothing cleared it again.
        // The envelope matters — real speech dips between syllables, and it
        // is those dips the floor is meant to measure.
        var i = 0
        val windows = segment(blocks =
            frames(400) { if (i++ % 5 < 3) tone(4_000) else tone(300) } +
                    frames(20) { silence() }
        )

        val covered = windows.sumOf { it.pcm.size }.toLong() * 1_000 / SAMPLE_RATE
        assertTrue("only ${covered}ms of 20s covered", covered > 15_000)
    }

    @Test
    fun `a sustained flat tone is treated as background`() {
        // The flip side of the property above: a sound with no envelope at
        // all is what a machine makes, not a person, and after the warm-up
        // the floor rises to meet it. Whisper is spared the decode.
        val windows = segment(blocks = frames(400) { tone(4_000) })

        val covered = windows.sumOf { it.pcm.size }.toLong() * 1_000 / SAMPLE_RATE
        assertTrue("covered ${covered}ms of 20s", covered < 4_000)
    }

    @Test
    fun `speech buried under louder noise stays closed`() {
        val rng = Random(11)
        val windows = segment(blocks =
            frames(200) { noise(6_000, rng) } +
                    frames(40) { tone(300) } +
                    frames(40) { noise(6_000, rng) }
        )

        // The 300-peak "speech" is 26 dB under the noise; nothing should open
        // on it, and the noise itself must not latch the gate either.
        val covered = windows.sumOf { it.pcm.size }.toLong() * 1_000 / SAMPLE_RATE
        assertTrue("covered ${covered}ms", covered < 4_000)
    }

    // -------------------------------------------------------------------------
    // End of stream
    // -------------------------------------------------------------------------

    @Test
    fun `flushes the final utterance when the stream ends mid-speech`() {
        // No trailing silence: the silence hang never fires. Before the flush
        // existed this segment was buffered and then silently dropped.
        val windows = segment(blocks = frames(20) { silence() } + frames(40) { tone(3_000) })

        assertEquals(1, windows.size)
        assertTrue(windows.single().isFinal)
    }

    @Test
    fun `does not flush a sub-minimum tail at end of stream`() {
        // 100 ms of speech is under MIN_SEGMENT_MS and is noise, not a caption.
        val windows = segment(blocks = frames(20) { silence() } + frames(2) { tone(3_000) })

        assertTrue(windows.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Threshold bounds
    // -------------------------------------------------------------------------

    @Test
    fun `threshold never drops below the configured minimum`() {
        val gate = SpeechSegmenter.NoiseFloorGate(
            slots = 400, warmupFrames = 20, quantile = 0.05f, margin = 2.0f,
            minThreshold = 24, maxThreshold = 16_000,
        )
        repeat(500) { gate.isSpeech(0) }

        assertEquals(0, gate.noiseFloor)
        assertEquals(24, gate.threshold)
        assertTrue(gate.isSpeech(25))
        assertTrue(!gate.isSpeech(24))
    }

    @Test
    fun `threshold never rises above the configured maximum`() {
        val gate = SpeechSegmenter.NoiseFloorGate(
            slots = 400, warmupFrames = 20, quantile = 0.05f, margin = 2.0f,
            minThreshold = 24, maxThreshold = 1_000,
        )
        repeat(500) { gate.isSpeech(30_000) }

        assertEquals(1_000, gate.threshold)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 50
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1_000
    }
}
