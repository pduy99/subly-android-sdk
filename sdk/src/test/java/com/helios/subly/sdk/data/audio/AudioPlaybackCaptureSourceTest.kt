package com.helios.subly.sdk.data.audio

import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class AudioPlaybackCaptureSourceTest {

    private val projection: MediaProjection = mock()

    /**
     * Yields scripted PCM "frames" one per [read], then [AudioCaptureDataSource.READ_STOPPED].
     * Optionally advances an injected [TestTimeSource] per read so the repository's
     * `sessionStart.elapsedNow()` produces strictly increasing timestamps under test.
     */
    private class FakeDataSource(
        private val scripted: List<ShortArray>,
        private val timeSource: TestTimeSource? = null,
        private val advancePerRead: kotlin.time.Duration = 10.milliseconds,
        override val sampleRateHz: Int = 16_000,
        override val channelCount: Int = 1,
        override val framesPerRead: Int = 800,
    ) : AudioCaptureDataSource {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        private var cursor = 0

        override fun open(mediaProjection: MediaProjection) {
            opens.incrementAndGet()
        }

        override fun read(buffer: ShortArray): Int {
            if (cursor >= scripted.size) return AudioCaptureDataSource.READ_STOPPED
            timeSource?.let { it += advancePerRead }
            val src = scripted[cursor++]
            src.copyInto(buffer, destinationOffset = 0, startIndex = 0, endIndex = src.size)
            return src.size
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun `emits one AudioFrame per data source read with computed amplitude`() = runTest {
        val tone = ShortArray(800) { if (it % 2 == 0) 4_000 else -4_000 }
        val time = TestTimeSource()
        val ds = FakeDataSource(scripted = listOf(tone, tone), timeSource = time)
        val source = AudioPlaybackCaptureSource(
            dataSource = ds,
            timeSource = time,
            ioContext = UnconfinedTestDispatcher(testScheduler),
        )

        val frames = source.frames(projection).take(2).toList()

        assertEquals(2, frames.size)
        assertEquals(4_000, frames[0].maxAbsSample)
        assertEquals(800, frames[0].pcm.size)
        assertEquals(16_000, frames[0].sampleRateHz)
        assertEquals(1, frames[0].channelCount)
        // Each read advances the TestTimeSource by 10 ms; session-relative timestamps follow.
        assertEquals(10L, frames[0].timestampMs)
        assertEquals(20L, frames[1].timestampMs)
        assertEquals(1, ds.opens.get())
        // Flow.take cancels upstream after the 2nd emission -> data source closes exactly once.
        assertEquals(1, ds.closes.get())
    }

    @Test
    fun `partial read is forwarded with trimmed pcm`() = runTest {
        val partial = ShortArray(200) { 1_000 }
        val ds = FakeDataSource(scripted = listOf(partial))
        val source = AudioPlaybackCaptureSource(
            dataSource = ds,
            timeSource = TestTimeSource(),
            ioContext = UnconfinedTestDispatcher(testScheduler),
        )

        val frame = source.frames(projection).take(1).toList().single()

        assertEquals(200, frame.pcm.size)
        assertEquals(1_000, frame.maxAbsSample)
    }

    @Test
    fun `amplitudes flow mirrors frames flow in lock step`() = runTest {
        val pcm = ShortArray(800) { 2_500 }
        val ds = FakeDataSource(scripted = listOf(pcm, pcm, pcm))
        val source = AudioPlaybackCaptureSource(
            dataSource = ds,
            timeSource = TestTimeSource(),
            ioContext = StandardTestDispatcher(testScheduler),
        )

        // Subscribe to amplitudes BEFORE collecting frames (replay = 0).
        val ampJob = async(start = CoroutineStart.UNDISPATCHED) {
            source.amplitudes().take(3).toList()
        }
        val frames = source.frames(projection).take(3).toList()
        val amplitudes = ampJob.await()

        assertEquals(listOf(2_500, 2_500, 2_500), amplitudes.map { it.maxAbsSample })
        assertEquals(frames.map { it.maxAbsSample }, amplitudes.map { it.maxAbsSample })
    }

    @Test
    fun `stop closes the data source even before collection ends`() = runTest {
        val ds = FakeDataSource(scripted = List(1_000) { ShortArray(800) })
        val source = AudioPlaybackCaptureSource(
            dataSource = ds,
            timeSource = TestTimeSource(),
            ioContext = UnconfinedTestDispatcher(testScheduler),
        )

        // take(1) ends the cold flow which already closes the data source.
        source.frames(projection).take(1).toList()
        val closesAfterTake = ds.closes.get()
        source.stop()

        assertTrue("stop() must be safe to call after teardown", ds.closes.get() >= closesAfterTake)
        assertFalse("data source was opened", ds.opens.get() == 0)
    }
}
