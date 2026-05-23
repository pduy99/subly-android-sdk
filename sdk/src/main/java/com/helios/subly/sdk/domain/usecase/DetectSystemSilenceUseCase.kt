package com.helios.subly.sdk.domain.usecase

import com.helios.subly.sdk.domain.model.Amplitude
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Pure-Kotlin silence detector backing the DRM-block fallback decision.
 *
 * Emits `true` once the input [Amplitude] stream has stayed at or below
 * [silenceEpsilon] for at least [silenceWindowMs] consecutive milliseconds, and
 * `false` again as soon as a louder sample arrives. Output is collapsed via
 * [distinctUntilChanged] so collectors only observe edges.
 *
 * Per PRD A2 the threshold must not be literal zero - codec dither and noise
 * floor will keep raw samples slightly above 0 even during true silence.
 *
 * @param silenceEpsilon Inclusive max-abs-sample considered "silent"
 *   (default ~ -60 dBFS for 16-bit PCM).
 * @param silenceWindowMs Minimum continuous silent duration before flagging
 *   (default 2000 ms, matching PRD).
 */
class DetectSystemSilenceUseCase(
    private val silenceEpsilon: Int = DEFAULT_EPSILON,
    private val silenceWindowMs: Long = DEFAULT_WINDOW_MS,
) {
    operator fun invoke(amplitudes: Flow<Amplitude>): Flow<Boolean> = flow {
        var silenceStartedAtMs: Long? = null
        var lastEmitted = false

        amplitudes.collect { sample ->
            val silentNow = sample.maxAbsSample <= silenceEpsilon
            val isSilenced = if (silentNow) {
                val start = silenceStartedAtMs ?: sample.timestampMs.also {
                    silenceStartedAtMs = it
                }
                (sample.timestampMs - start) >= silenceWindowMs
            } else {
                silenceStartedAtMs = null
                false
            }
            if (isSilenced != lastEmitted) {
                lastEmitted = isSilenced
                emit(isSilenced)
            }
        }
    }.distinctUntilChanged()

    companion object {
        const val DEFAULT_EPSILON: Int = 32 // ~ -60 dBFS for 16-bit PCM
        const val DEFAULT_WINDOW_MS: Long = 2_000L
    }
}
