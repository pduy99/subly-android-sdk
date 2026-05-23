package com.helios.subly.sdk.data.audio

import android.media.projection.MediaProjection

/**
 * Platform shim over a PCM capture device (today: `AudioRecord` driven by
 * `AudioPlaybackCaptureConfiguration`).
 *
 * Lifecycle is explicit and single-shot per session:
 * `open` -> repeated `read` -> `close`. Implementations are NOT required to be
 * reentrant; the repository serializes calls.
 */
internal interface AudioCaptureDataSource {

    /** PCM sample rate this source produces (e.g. 16_000). */
    val sampleRateHz: Int

    /** Number of interleaved channels in each [read] (1 = mono, 2 = stereo). */
    val channelCount: Int

    /** Preferred number of samples per [read] call - aligns with the silence-detector cadence. */
    val framesPerRead: Int

    /**
     * Acquire the platform recorder. Must be called before [read].
     * @throws IllegalStateException if initialization fails.
     */
    fun open(mediaProjection: MediaProjection)

    /**
     * Blocking read into [buffer]. Returns:
     * - a positive sample count on success,
     * - [READ_STOPPED] when the source has been closed externally (loop should break),
     * - 0 or negative transient codes the caller may safely skip.
     */
    fun read(buffer: ShortArray): Int

    /** Idempotent. Safe to call from any thread; releases native resources. */
    fun close()

    companion object {
        /** Sentinel returned by [read] when the source is no longer capturing. */
        const val READ_STOPPED: Int = Int.MIN_VALUE
    }
}
