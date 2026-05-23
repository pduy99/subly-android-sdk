package com.helios.subly.sdk.data.audio

import kotlin.math.abs

/**
 * Pure helpers for reasoning about 16-bit linear PCM amplitudes.
 *
 * Kept framework-free so the audio pipeline can be unit-tested with synthetic
 * buffers (no `AudioRecord`, no Robolectric).
 */
internal object PcmAmplitude {

    /**
     * Loudest absolute sample in [pcm] over [0, length). Reads as `Int` to avoid
     * the `Short.MIN_VALUE.absoluteValue` overflow trap (`-32768` -> `32768`).
     *
     * @param length Number of valid samples in [pcm] (may be smaller than the
     *   array's allocated size when reused as a ring buffer).
     */
    fun maxAbsSample(pcm: ShortArray, length: Int = pcm.size): Int {
        var max = 0
        var i = 0
        while (i < length) {
            val v = abs(pcm[i].toInt())
            if (v > max) max = v
            i++
        }
        return max
    }
}
