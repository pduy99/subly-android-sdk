package com.helios.subly.sdk.domain.model

/**
 * Public descriptor for a Whisper ASR checkpoint variant. Surfaced by
 * `SublyModelRegistry` so the Consumer's downloader UI can render disk-size
 * hints, drive `DownloadManager`, and gate engine start until the chosen
 * variant is on-disk.
 *
 * @property id Stable identifier (e.g. `"tiny-q5_1"`). Use as the key when
 *   calling registry methods.
 * @property displayName Human-readable label for UI rows.
 * @property approxSizeBytes Used for "X MB download" copy + pre-flight free-
 *   space checks. Not authoritative.
 * @property bundledInAssets True iff the variant ships in the APK and is
 *   guaranteed available without a network round-trip.
 * @property downloadUrl HTTPS URL the host can hand to `DownloadManager` /
 *   any HTTP client. `null` for variants that don't support remote install
 *   (e.g. the bundled `tiny-q5_1`).
 * @property sha256 Optional hex digest used by the registry's install hook
 *   to verify a download before promoting it into the SDK's private dir.
 *   `null` means the registry will skip verification (trust the transport).
 */
data class WhisperModelInfo(
    val id: String,
    val displayName: String,
    val approxSizeBytes: Long,
    val bundledInAssets: Boolean,
    val downloadUrl: String?,
    val sha256: String?,
)
