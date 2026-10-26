package com.helios.subly.core.model

/**
 * Snapshot of the loudest 16-bit PCM sample in a capture frame.
 */
data class Amplitude(
    val maxAbsSample: Int,
    val timestampMs: Long,
)
