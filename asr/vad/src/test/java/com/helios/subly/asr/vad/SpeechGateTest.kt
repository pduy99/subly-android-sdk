package com.helios.subly.asr.vad

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeechGateTest {

    // ---- fakes -----------------------------------------------------------

    /** Feeds a scripted probability per window; repeats the last one. */
    private class FakeSession(private val script: List<Float>) : VadBackend.Session {
        var windowsSeen = 0
            private set
        var resets = 0
            private set
        var released = false
            private set

        override fun probability(window: FloatArray): Float {
            val value = script.getOrElse(windowsSeen) { script.lastOrNull() ?: 0f }
            windowsSeen++
            return value
        }

        override fun reset() {
            resets++
        }

        override fun release() {
            released = true
        }
    }

    private class FakeBackend(
        private val available: Boolean = true,
        val session: FakeSession = FakeSession(listOf(0f)),
        private val openThrows: Boolean = false,
    ) : VadBackend {
        override fun isAvailable(): Boolean = available
        override fun open(modelPath: String): VadBackend.Session {
            if (openThrows) throw IllegalStateException("bad checkpoint")
            return session
        }
    }

    private class FakeLoader(
        private val ready: Boolean = true,
        private val fails: Boolean = false,
    ) : VadModelLoader {
        override fun modelFile(): File = File("/tmp/silero_vad.onnx")
        override fun isReady(): Boolean = ready
        override fun provisionWithProgress(): Flow<Float> = flow {
            emit(0.5f)
            if (fails) throw java.io.IOException("network is down")
            emit(1f)
        }
    }

    // Small, explicit tuning so the window arithmetic in each test is legible:
    // the gate closes after 5 sub-threshold windows and looks 2 windows ahead.
    private val closeAfterMs = 5 * SileroVadModel.WINDOW_MS
    private val lookahead = 2

    private fun gate(
        backend: VadBackend,
        loader: VadModelLoader = FakeLoader(),
        threshold: Float = 0.5f,
        lookaheadWindows: Int = lookahead,
    ) = SpeechGate(loader, backend, threshold, closeAfterMs, lookaheadWindows)

    private val window = SileroVadModel.WINDOW_SIZE

    /** [windows] whole analysis windows of a constant, audible sample value. */
    private fun audio(windows: Int, value: Short = 5_000) = ShortArray(windows * window) { value }

    private fun ShortArray.windowAt(i: Int) = copyOfRange(i * window, (i + 1) * window)

    // ---- availability / preparation --------------------------------------

    @Test
    fun `no sherpa runtime leaves the gate inactive and audio untouched`() = runTest {
        val gate = gate(FakeBackend(available = false))

        val progress = gate.prepare().toList()

        assertEquals(1f, progress.last())
        assertFalse(gate.isActive)
        // An inactive gate is the identity: same array back, and no delay.
        val pcm = audio(4)
        assertArrayEquals(pcm, gate.process(pcm))
    }

    @Test
    fun `a checkpoint that never landed leaves the gate inactive`() = runTest {
        val gate = gate(FakeBackend(), loader = FakeLoader(ready = false))

        gate.prepare().toList()

        assertFalse(gate.isActive)
    }

    @Test
    fun `a failed download disables the gate instead of failing preparation`() = runTest {
        // The gate provisions inside the ASR engine's own prepareModel flow,
        // which must terminate with Ready or Error and must not throw. A dead
        // network while fetching a 0.6 MB accuracy improvement must not be
        // what fails a captioning session.
        val gate = gate(FakeBackend(), loader = FakeLoader(ready = false, fails = true))

        val progress = gate.prepare().toList()

        assertEquals(1f, progress.last())
        assertFalse(gate.isActive)
    }

    @Test
    fun `a failing native init leaves the gate inactive rather than throwing`() = runTest {
        val gate = gate(FakeBackend(openThrows = true))

        val progress = gate.prepare().toList()

        assertEquals(1f, progress.last())
        assertFalse(gate.isActive)
    }

    @Test
    fun `preparing an already-open gate short-circuits`() = runTest {
        val gate = gate(FakeBackend())
        gate.prepare().toList()

        assertEquals(listOf(1f), gate.prepare().toList())
        assertTrue(gate.isActive)
    }

    // ---- the delay line ---------------------------------------------------

    @Test
    fun `output is held back by the lookahead and released by flush`() = runTest {
        val gate = gate(FakeBackend(session = FakeSession(listOf(0.9f))))
        gate.prepare().toList()

        val out = gate.process(audio(6))

        assertEquals("6 windows in, 2 held back", (6 - lookahead) * window, out.size)
        assertEquals("flush releases the rest", lookahead * window, gate.flushShort().size)
        assertEquals("and nothing after that", 0, gate.flushShort().size)
    }

    @Test
    fun `no audio is lost across ragged frame boundaries`() = runTest {
        val gate = gate(FakeBackend(session = FakeSession(listOf(0.9f))))
        gate.prepare().toList()

        // 800 samples is the SDK's 50 ms capture frame — deliberately not a
        // multiple of the 512-sample analysis window.
        var emitted = 0
        repeat(20) { emitted += gate.process(ShortArray(800) { 1_000 }).size }
        emitted += gate.flushShort().size

        // Everything that completed a window comes out; only the partial
        // window at the very end is still buffered.
        assertEquals(20 * 800 / window * window, emitted)
    }

    // ---- gating behaviour -------------------------------------------------

    @Test
    fun `speech passes through untouched`() = runTest {
        val gate = gate(FakeBackend(session = FakeSession(listOf(0.9f))))
        gate.prepare().toList()

        val pcm = audio(10)
        val out = gate.process(pcm) + gate.flushShort()

        assertArrayEquals(pcm, out)
    }

    @Test
    fun `sustained non-speech is muted only after the close delay`() = runTest {
        val gate = gate(FakeBackend(session = FakeSession(listOf(0.01f))))
        gate.prepare().toList()

        val out = gate.process(audio(12)) + gate.flushShort()

        // The gate stays open for 5 windows' worth of silence, so windows 0-3
        // are audible and muting starts at window 4.
        for (i in 0 until 4) {
            assertTrue("window $i should be audible", out.windowAt(i).any { it.toInt() != 0 })
        }
        for (i in 4 until 12) {
            assertTrue("window $i should be muted", out.windowAt(i).all { it.toInt() == 0 })
        }
    }

    @Test
    fun `audio just before a speech onset is not muted`() = runTest {
        // THE regression this class exists to avoid. Measured on the accuracy
        // benchmark before the lookahead existed: the gate ate the first word
        // of a caption every time the recogniser had just endpointed on a
        // pause — 記事の温度… came back as の温度…, Congress began… as
        // Iris began…. A verdict cannot be applied to audio already emitted,
        // so the gate has to see the onset coming.
        val script = List(10) { 0.01f } + List(4) { 0.9f }
        val gate = gate(FakeBackend(session = FakeSession(script)))
        gate.prepare().toList()

        val out = gate.process(audio(script.size)) + gate.flushShort()

        // Speech starts at window 10. Windows 8 and 9 are non-speech in their
        // own right, but sit inside the lookahead, so they survive.
        for (i in 8 until script.size) {
            assertTrue("window $i should be audible", out.windowAt(i).any { it.toInt() != 0 })
        }
        // Window 7 is beyond the lookahead and stays muted.
        assertTrue(out.windowAt(7).all { it.toInt() == 0 })
    }

    @Test
    fun `an unknown probability is treated as speech`() = runTest {
        val gate = gate(
            FakeBackend(session = FakeSession(listOf(VadBackend.PROBABILITY_UNKNOWN)))
        )
        gate.prepare().toList()

        val pcm = audio(12)
        assertArrayEquals(pcm, gate.process(pcm) + gate.flushShort())
    }

    @Test
    fun `windows are assembled across frame boundaries`() = runTest {
        val session = FakeSession(listOf(0.9f))
        val gate = gate(FakeBackend(session = session))
        gate.prepare().toList()

        // 8 frames x 800 samples = 6400 samples = 12 whole 512-sample windows
        // plus a remainder, so the count proves nothing was dropped or
        // double-counted at the seams.
        repeat(8) { gate.process(ShortArray(800)) }

        assertEquals(6_400 / window, session.windowsSeen)
    }

    @Test
    fun `the source buffer is never mutated`() = runTest {
        val gate = gate(FakeBackend(session = FakeSession(listOf(0.01f))))
        gate.prepare().toList()

        val pcm = audio(12)
        val copy = pcm.copyOf()
        gate.process(pcm)

        assertArrayEquals(copy, pcm)
    }

    @Test
    fun `the float overload gates the same way`() = runTest {
        val script = List(10) { 0.01f } + List(4) { 0.9f }
        val gate = gate(FakeBackend(session = FakeSession(script)))
        gate.prepare().toList()

        val pcm = FloatArray(script.size * window) { 0.25f }
        val out = gate.process(pcm) + gate.flushFloat()

        assertEquals(pcm.size, out.size)
        assertTrue(out.copyOfRange(8 * window, 9 * window).any { it != 0f })
        assertTrue(out.copyOfRange(7 * window, 8 * window).all { it == 0f })
    }

    // ---- lifecycle --------------------------------------------------------

    @Test
    fun `reset clears native state, drops held audio, and reopens the gate`() = runTest {
        val session = FakeSession(listOf(0.01f))
        val gate = gate(FakeBackend(session = session))
        gate.prepare().toList()
        gate.process(audio(12))

        gate.reset()

        assertEquals(1, session.resets)
        assertEquals("held-back audio is dropped, not replayed", 0, gate.flushShort().size)
        val pcm = audio(4)
        assertArrayEquals(pcm, gate.process(pcm) + gate.flushShort())
    }

    @Test
    fun `release frees the session and degrades to pass-through`() = runTest {
        val session = FakeSession(listOf(0.01f))
        val gate = gate(FakeBackend(session = session))
        gate.prepare().toList()

        gate.release()
        gate.release()

        assertTrue(session.released)
        assertFalse(gate.isActive)
        val pcm = audio(12)
        assertArrayEquals(pcm, gate.process(pcm))
    }
}
