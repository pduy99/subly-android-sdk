package com.helios.subly.sdk.domain.repository

import com.helios.subly.sdk.domain.model.WhisperModelInfo
import java.io.File

/**
 * Public read/prepare boundary for both on-device model families:
 *
 *  1. **Whisper ASR checkpoints** (bundled `tiny-q5_1` + downloadable `base` /
 *     `small` variants). Hosts drive downloads with their own transport
 *     (typically `DownloadManager` for system-managed progress + resume) and
 *     hand the resulting file back via [installWhisperModel].
 *  2. **ML Kit translation pairs** (~30 MB each, per `(source, target)` BCP-47
 *     pair). The pair download is owned by Play Services and can only be
 *     driven via ML Kit's own API, so [ensureTranslationPair] is the single
 *     entry point.
 */
interface SublyModelRegistry {

    /** All Whisper variants known to the SDK (bundled and downloadable). */
    val whisperModels: List<WhisperModelInfo>

    /**
     * `true` iff the default Whisper checkpoint (the in-APK `tiny-q5_1`) is
     * resolvable on-disk. The engine refuses to emit packets without it, so
     * the Consumer should block Start until this is satisfied.
     */
    fun isDefaultWhisperReady(): Boolean

    /** Per-variant readiness check (disk-presence). */
    fun isWhisperReady(modelId: String): Boolean

    /**
     * Promote a host-downloaded Whisper checkpoint into the SDK's private
     * models directory.
     *
     * The Consumer is expected to download the variant's
     * [WhisperModelInfo.downloadUrl] with its own transport (e.g.
     * `DownloadManager`) into any scratch location, then call this. The
     * registry verifies the file size against [WhisperModelInfo.approxSizeBytes]
     * within a ±25% tolerance, optionally verifies SHA-256 when
     * [WhisperModelInfo.sha256] is present, and atomically renames the file
     * into the SDK's private dir on success. The scratch file is consumed
     * regardless of outcome.
     */
    fun installWhisperModel(modelId: String, sourceFile: File): InstallResult

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

/** Outcome of [SublyModelRegistry.installWhisperModel]. */
sealed class InstallResult {
    object Success : InstallResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : InstallResult()
    object UnknownModel : InstallResult()
    object BundledVariant : InstallResult()
}
