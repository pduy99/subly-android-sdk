package com.helios.subly.sdk.domain.repository

/**
 * Public read/prepare boundary for on-device model families:
 *
 *  **ML Kit translation pairs** (~30 MB each, per `(source, target)` BCP-47
 *  pair). The pair download is owned by Play Services and can only be
 *  driven via ML Kit's own API, so [ensureTranslationPair] is the single
 *  entry point.
 */
interface SublyModelRegistry {

    /**
     * Ensures (downloads if needed) the ML Kit translation pair backing
     * `source -> target`. Idempotent after first success. Pass-through when
     * `source == target` (always returns [DownloadResult.Success]).
     *
     * Note: ML Kit doesn't expose progress percentage during a pair
     * download. Hosts that need a foreground notification should wrap this
     * call in their own `Service` with an indeterminate progress UI.
     */
    suspend fun ensureTranslationPair(
        sourceBcp47: String,
        targetBcp47: String,
    ): DownloadResult
}

