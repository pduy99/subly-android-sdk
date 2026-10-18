package com.helios.subly.benchmark.latency

import android.media.projection.MediaProjection
import android.os.Debug
import com.helios.subly.audio.api.ProjectionlessAudioCapture
import com.helios.subly.benchmark.results.MemoryRecord
import com.helios.subly.core.model.Amplitude
import com.helios.subly.core.model.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * Records, per audio frame handed to the pipeline, the wall time it was
 * emitted and whether it carried speech. That is what turns "caption
 * appeared at T" into "caption appeared N ms after the speaker stopped".
 *
 * Voicing is a plain RMS threshold. The benchmark clips are studio-clean
 * read speech, where that is reliable; it would not be on music or noise,
 * which is why the pipeline itself uses Silero rather than this.
 */
class VoicedFrameTracker(private val rmsThreshold: Double = DEFAULT_RMS_THRESHOLD) {

    data class Frame(val wallNanos: Long, val voiced: Boolean)

    private val frames = CopyOnWriteArrayList<Frame>()

    fun record(frame: AudioFrame) {
        frames += Frame(System.nanoTime(), rms(frame.pcm) >= rmsThreshold)
    }

    /** Wall time of the last voiced frame emitted at or before [atNanos]. */
    fun lastVoicedAtOrBefore(atNanos: Long): Long? =
        frames.lastOrNull { it.voiced && it.wallNanos <= atNanos }?.wallNanos

    /**
     * Wall time speech resumed after [afterNanos]: the first voiced frame
     * emitted after it, or null if none.
     */
    fun firstVoicedAfter(afterNanos: Long): Long? =
        frames.firstOrNull { it.voiced && it.wallNanos > afterNanos }?.wallNanos

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size)
    }

    companion object {
        /** About -36 dBFS: well above the clips' noise floor, below quiet syllables. */
        const val DEFAULT_RMS_THRESHOLD = 500.0
    }
}

/** Wraps a capture so every frame it emits passes through [tracker]. */
class TrackedCapture(
    private val inner: ProjectionlessAudioCapture,
    private val tracker: VoicedFrameTracker,
) : ProjectionlessAudioCapture {
    override fun frames(): Flow<AudioFrame> = inner.frames().onEach(tracker::record)
    override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
    override fun amplitudes(): Flow<Amplitude> = inner.amplitudes()
    override fun stop() = inner.stop()
}

/**
 * A capture the test pushes PCM into, for audio that is not a file: the
 * harness-side equivalent of a microphone. [end] completes the stream, which
 * is what lets the session flush its last sentence.
 */
class PushAudioCapture(private val sampleRateHz: Int = 16_000) : ProjectionlessAudioCapture {
    private val channel = Channel<AudioFrame>(Channel.UNLIMITED)
    private var timestampMs = 0L

    fun push(pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + FRAME_SAMPLES, pcm.size)
            val chunk = pcm.copyOfRange(offset, end)
            channel.trySend(
                AudioFrame(
                    pcm = chunk,
                    sampleRateHz = sampleRateHz,
                    channelCount = 1,
                    timestampMs = timestampMs,
                    maxAbsSample = chunk.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0,
                )
            )
            timestampMs += chunk.size * 1_000L / sampleRateHz
            offset = end
        }
    }

    fun end() {
        channel.close()
    }

    override fun frames(): Flow<AudioFrame> = channel.receiveAsFlow()
    override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = frames()
    override fun amplitudes(): Flow<Amplitude> = emptyFlow()
    override fun stop() {
        channel.close()
    }

    private companion object {
        /** 50 ms at 16 kHz, the production capture's frame size. */
        const val FRAME_SAMPLES = 800
    }
}

/**
 * Samples this process's memory while a run is in progress.
 *
 * Three numbers, because "memory" means three different things here:
 *  - peak RSS from the kernel (`VmHWM`), exact, but it counts the
 *    memory-mapped 1.6 GB checkpoint's resident pages, which the system can
 *    drop under pressure;
 *  - peak PSS, sampled, the number Android's low-memory killer weighs;
 *  - peak native heap, sampled, what inference actually allocated.
 */
class MemorySampler(private val intervalMs: Long = 250) {
    private var job: Job? = null
    @Volatile private var peakPssKb = 0L
    @Volatile private var peakNativeBytes = 0L
    @Volatile private var samples = 0

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.Default) {
            val info = Debug.MemoryInfo()
            while (isActive) {
                Debug.getMemoryInfo(info)
                peakPssKb = maxOf(peakPssKb, info.totalPss.toLong())
                peakNativeBytes = maxOf(peakNativeBytes, Debug.getNativeHeapAllocatedSize())
                samples++
                delay(intervalMs)
            }
        }
    }

    fun stop(): MemoryRecord {
        job?.cancel()
        return MemoryRecord(
            peakRssMb = readVmHwmKb() / 1024.0,
            peakPssMb = peakPssKb / 1024.0,
            peakNativeHeapMb = peakNativeBytes / (1024.0 * 1024.0),
            samples = samples,
        )
    }

    private fun readVmHwmKb(): Long =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
}
