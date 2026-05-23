package com.helios.subly.sdk.domain.model

/**
 * Snapshot of the loudest 16-bit PCM sample in a capture frame.
 *
 * @property maxAbsSample Absolute value of the loudest sample in the frame
 *   (0..[Short.MAX_VALUE]). Used by [com.helios.subly.sdk.domain.usecase.DetectSystemSilenceUseCase]
 *   to decide if the system audio path is silenced (PRD A2: epsilon, 2000 ms).
 * @property timestampMs Monotonic milliseconds since capture session start (matches the
 *   accompanying [com.helios.subly.sdk.domain.model.AudioFrame.timestampMs]).
 */
data class Amplitude(
    val maxAbsSample: Int,
    val timestampMs: Long,
)
