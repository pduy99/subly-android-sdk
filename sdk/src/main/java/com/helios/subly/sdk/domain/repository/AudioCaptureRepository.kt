package com.helios.subly.sdk.domain.repository

import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.Amplitude
import com.helios.subly.sdk.domain.model.AudioFrame
import kotlinx.coroutines.flow.Flow

/**
 * Boundary over the primary audio playback capture pipeline.
 *
 * Implementations wrap `AudioRecord` + `AudioPlaybackCaptureConfiguration` and
 * are expected to be cold: collecting [frames] starts capture, cancelling the
 * collecting scope (or calling [stop]) releases the recorder.
 */
interface AudioCaptureRepository {

    /**
     * Cold stream of PCM frames captured from system audio playback.
     *
     * @param mediaProjection User-granted projection token; must originate from
     *   a running `mediaProjection` foreground service (PRD A3).
     */
    fun frames(mediaProjection: MediaProjection): Flow<AudioFrame>

    /**
     * Side-channel of per-frame amplitudes for silence / DRM-block detection.
     * Emits in lock-step with [frames] from the same capture session.
     */
    fun amplitudes(): Flow<Amplitude>

    /** Eagerly release capture resources held by the implementation. */
    fun stop()
}
