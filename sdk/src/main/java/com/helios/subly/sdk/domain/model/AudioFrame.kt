package com.helios.subly.sdk.domain.model

/**
 * One PCM frame pulled from `AudioPlaybackCaptureConfiguration`.
 *
 * Held as `ShortArray` (16-bit linear PCM) rather than `ByteArray` so amplitude
 * checks and feature extraction don't repeatedly re-decode endianness.
 *
 * @property pcm 16-bit signed PCM samples, mono or interleaved stereo.
 * @property sampleRateHz Source sample rate (typically 48_000 on modern devices).
 * @property channelCount 1 (mono) or 2 (stereo).
 * @property timestampMs Monotonic wall-clock time the frame was read.
 * @property maxAbsSample Pre-computed loudest absolute sample in [pcm].
 */
data class AudioFrame(
    val pcm: ShortArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val timestampMs: Long,
    val maxAbsSample: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return sampleRateHz == other.sampleRateHz &&
            channelCount == other.channelCount &&
            timestampMs == other.timestampMs &&
            maxAbsSample == other.maxAbsSample &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channelCount
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + maxAbsSample
        return result
    }
}
