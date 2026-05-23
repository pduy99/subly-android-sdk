package com.helios.subly.sdk.data.vision

import android.media.projection.MediaProjection

/**
 * Platform shim over a `MediaProjection`-backed screen capture device
 * (today: `VirtualDisplay` + `ImageReader`).
 *
 * Lifecycle is explicit and single-shot per session:
 * `open` -> repeated suspending `readFrame` -> `close`. Pull-based for parity
 * with [AudioCaptureDataSource]; the Android-side `ImageReader` callback is
 * bridged into a suspending channel inside the implementation.
 *
 * Throttling lives in the implementation (not the repository) so dropped
 * frames are discarded before any pixel copy.
 */
internal interface VirtualDisplayDataSource {

    /**
     * Acquire the platform projection / display / reader. Must be called
     * before [readFrame].
     *
     * @param targetFps Upper bound on frame rate; <= 0 disables throttling.
     * @throws IllegalStateException if initialization fails or the source is
     *   already open.
     */
    fun open(mediaProjection: MediaProjection, targetFps: Int)

    /**
     * Suspends until the next captured frame is available. Returns `null`
     * once the source has been closed (loop should break).
     */
    suspend fun readFrame(): RawVisionFrame?

    /** Idempotent. Safe to call from any thread; releases native resources. */
    fun close()
}

/**
 * Untimestamped frame as the platform reads it. The repository attaches a
 * session-relative timestamp (parity with the audio path).
 */
internal data class RawVisionFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawVisionFrame) return false
        return width == other.width &&
            height == other.height &&
            rowStrideBytes == other.rowStrideBytes &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rowStrideBytes
        return result
    }
}
