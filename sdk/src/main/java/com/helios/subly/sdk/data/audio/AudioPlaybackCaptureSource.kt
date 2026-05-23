package com.helios.subly.sdk.data.audio

import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.Amplitude
import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.repository.AudioCaptureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * [AudioCaptureRepository] backed by a pluggable [AudioCaptureDataSource].
 *
 * The platform-specific recorder lifecycle (`AudioRecord` open/read/close)
 * lives behind [dataSource]; this class only:
 * - shapes raw PCM reads into [AudioFrame]s,
 * - tees per-frame amplitudes through a `SharedFlow` so the silence detector
 *   can subscribe without re-opening capture,
 * - manages Flow cancellation and idempotent teardown.
 *
 * Keeping the repository framework-free is the dependency-direction win: it
 * can be unit-tested with a fake data source that yields canned PCM, and the
 * data source can be swapped (Oboe/AAudio, captured-file source for QA)
 * without touching this orchestration.
 */
@OptIn(ExperimentalTime::class)
internal class AudioPlaybackCaptureSource(
    private val dataSource: AudioCaptureDataSource,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : AudioCaptureRepository {

    private val amplitudeFlow = MutableSharedFlow<Amplitude>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun frames(mediaProjection: MediaProjection): Flow<AudioFrame> = flow {
        dataSource.open(mediaProjection)
        // Session-relative monotonic origin. Timestamps are deltas-from-open, which is what the
        // silence detector (and any downstream caption alignment) actually needs
        val sessionStart = timeSource.markNow()
        try {
            val buffer = ShortArray(dataSource.framesPerRead)
            while (currentCoroutineContext().isActive) {
                val read = dataSource.read(buffer)
                if (read == AudioCaptureDataSource.READ_STOPPED) break
                if (read <= 0) continue // transient (ERROR_BAD_VALUE etc.) - skip
                val pcm = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                val maxAbs = PcmAmplitude.maxAbsSample(pcm, read)
                val ts = sessionStart.elapsedNow().inWholeMilliseconds
                amplitudeFlow.tryEmit(Amplitude(maxAbs, ts))
                emit(
                    AudioFrame(
                        pcm = pcm,
                        sampleRateHz = dataSource.sampleRateHz,
                        channelCount = dataSource.channelCount,
                        timestampMs = ts,
                        maxAbsSample = maxAbs,
                    ),
                )
            }
        } finally {
            dataSource.close()
        }
    }.flowOn(ioContext)

    override fun amplitudes(): Flow<Amplitude> = amplitudeFlow.asSharedFlow()

    override fun stop() {
        dataSource.close()
    }
}
