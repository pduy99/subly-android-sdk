package com.helios.subly.sdk.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAmplitudeTest {

    @Test
    fun `silent buffer reports zero amplitude`() {
        val pcm = ShortArray(800) // 50 ms at 16 kHz
        assertEquals(0, PcmAmplitude.maxAbsSample(pcm))
    }

    @Test
    fun `finds loudest positive sample`() {
        val pcm = shortArrayOf(10, -5, 1234, 3, -200)
        assertEquals(1234, PcmAmplitude.maxAbsSample(pcm))
    }

    @Test
    fun `finds loudest negative sample without overflow`() {
        // Short.MIN_VALUE.absoluteValue overflows back to MIN_VALUE in Short space;
        // the helper must promote to Int before taking abs.
        val pcm = shortArrayOf(0, Short.MIN_VALUE, 0)
        assertEquals(32_768, PcmAmplitude.maxAbsSample(pcm))
    }

    @Test
    fun `honors explicit length when buffer is reused as a ring`() {
        val pcm = shortArrayOf(100, 200, 30_000, 50)
        // Only the first two samples are "valid"; the loud 30_000 should be ignored.
        assertEquals(200, PcmAmplitude.maxAbsSample(pcm, length = 2))
    }
}
