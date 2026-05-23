package com.helios.subly.sdk.data.vision

import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VirtualDisplayCaptureSourceTest {

    private val fakeProjection: MediaProjection = org.mockito.kotlin.mock()

    private class FakeDataSource : VirtualDisplayDataSource {
        var openedFps: Int = -1
        var closed: Int = 0
        // Mirror the production implementation's channel-bridged shape.
        private val channel = Channel<RawVisionFrame>(capacity = Channel.UNLIMITED)

        override fun open(mediaProjection: MediaProjection, targetFps: Int) {
            openedFps = targetFps
        }

        override suspend fun readFrame(): RawVisionFrame? =
            channel.receiveCatching().getOrNull()

        override fun close() {
            closed++
            channel.close()
        }

        fun push(frame: RawVisionFrame) {
            channel.trySend(frame)
        }
    }

    @Test
    fun `bridges raw frames to VisionFrame with session-relative timestamps`() = runTest {
        val ds = FakeDataSource()
        val timeSource = TestTimeSource()
        val repo = VirtualDisplayCaptureSource(
            dataSource = ds,
            timeSource = timeSource,
            ioContext = StandardTestDispatcher(testScheduler),
        )

        val job = async(start = CoroutineStart.UNDISPATCHED) {
            repo.frames(fakeProjection, targetFps = 2).take(2).toList()
        }
        // Let the flow's first readFrame() suspend on the channel, then push
        // each frame interleaved with virtual-time advancement so the
        // consumer drains before the next push and the timestamp reflects
        // the elapsed session time at emit.
        advanceUntilIdle()
        ds.push(RawVisionFrame(ByteArray(4), width = 1, height = 1, rowStrideBytes = 4))
        advanceUntilIdle()
        timeSource += 100.milliseconds
        ds.push(RawVisionFrame(ByteArray(4) { 0xFF.toByte() }, width = 1, height = 1, rowStrideBytes = 4))
        advanceUntilIdle()

        val collected = job.await()
        assertEquals(2, collected.size)
        assertEquals(0L, collected[0].timestampMs)
        assertEquals(100L, collected[1].timestampMs)
        assertEquals(2, ds.openedFps)
        assertTrue(collected[1].pixels.all { it == 0xFF.toByte() })
    }

    @Test
    fun `flow cancellation closes the data source`() = runTest {
        val ds = FakeDataSource()
        val repo = VirtualDisplayCaptureSource(
            dataSource = ds,
            timeSource = TestTimeSource(),
            ioContext = StandardTestDispatcher(testScheduler),
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.frames(fakeProjection, targetFps = 1).take(1).toList()
        }
        advanceUntilIdle()
        ds.push(RawVisionFrame(ByteArray(4), 1, 1, 4))
        job.join()

        assertEquals(1, ds.closed)
    }

    @Test
    fun `stop closes the data source idempotently`() {
        val ds = FakeDataSource()
        val repo = VirtualDisplayCaptureSource(
            dataSource = ds,
            timeSource = TestTimeSource(),
        )
        repo.stop()
        repo.stop()
        assertEquals(2, ds.closed)
    }
}
