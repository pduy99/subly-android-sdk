package com.helios.subly.sdk.internal

import android.content.Context
import android.util.Log
import com.helios.subly.sdk.data.ai.WhisperModel
import com.helios.subly.sdk.data.ai.WhisperModelLoader
import com.helios.subly.sdk.data.translate.MlKitTranslator
import com.helios.subly.sdk.domain.model.WhisperModelInfo
import com.helios.subly.sdk.domain.repository.DownloadResult
import com.helios.subly.sdk.domain.repository.InstallResult
import com.helios.subly.sdk.domain.repository.SublyModelRegistry
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import java.io.File
import java.security.MessageDigest

/**
 * Default [SublyModelRegistry]. Backed by [WhisperModelLoader] for ASR
 * presence checks and a private [TranslatorRepository] instance for NMT
 * pair downloads.
 */
internal class DefaultSublyModelRegistry(
    private val context: Context,
    private val translator: TranslatorRepository = MlKitTranslator(),
) : SublyModelRegistry {

    private val loader: WhisperModelLoader by lazy { WhisperModelLoader(context) }

    override val whisperModels: List<WhisperModelInfo> = WhisperModel.entries.map { it.toInfo() }

    override fun isDefaultWhisperReady(): Boolean = loader.resolve(WhisperModel.Default) != null

    override fun isWhisperReady(modelId: String): Boolean {
        val variant = WhisperModel.entries.firstOrNull { it.id == modelId } ?: return false
        return loader.resolve(variant) != null
    }

    override fun installWhisperModel(modelId: String, sourceFile: File): InstallResult {
        val variant = WhisperModel.entries.firstOrNull { it.id == modelId }
            ?: return InstallResult.UnknownModel
        if (variant.bundledInAssets) return InstallResult.BundledVariant
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return InstallResult.Failed("source missing or empty: ${sourceFile.absolutePath}")
        }

        val sizeOk = sourceFile.length() in variant.minAcceptableSize()..variant.maxAcceptableSize()
        if (!sizeOk) {
            sourceFile.delete()
            return InstallResult.Failed(
                "size ${sourceFile.length()} outside ±25% of expected ${variant.approxSizeBytes}",
            )
        }

        variant.sha256?.let { expected ->
            val actual = runCatching { sourceFile.sha256Hex() }.getOrNull()
            if (!actual.equals(expected, ignoreCase = true)) {
                sourceFile.delete()
                return InstallResult.Failed("sha256 mismatch (expected $expected, got $actual)")
            }
        }

        val dest = loader.downloadTarget(variant)
        return runCatching {
            dest.parentFile?.mkdirs()
            if (!sourceFile.renameTo(dest)) {
                // Cross-filesystem rename can fail; fall back to copy.
                sourceFile.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                sourceFile.delete()
            }
            Log.i(TAG, "Installed ${variant.assetName} (${dest.length()} bytes) -> ${dest.absolutePath}")
            InstallResult.Success
        }.getOrElse { t ->
            InstallResult.Failed("rename/copy failed: ${t.message}", t)
        }
    }

    override suspend fun ensureTranslationPair(
        sourceBcp47: String,
        targetBcp47: String,
    ): DownloadResult = translator.ensureModel(sourceBcp47, targetBcp47)

    private fun WhisperModel.toInfo(): WhisperModelInfo = WhisperModelInfo(
        id = id,
        displayName = displayName,
        approxSizeBytes = approxSizeBytes,
        bundledInAssets = bundledInAssets,
        downloadUrl = downloadUrl,
        sha256 = sha256,
    )

    private fun WhisperModel.minAcceptableSize(): Long = (approxSizeBytes * 75) / 100
    private fun WhisperModel.maxAcceptableSize(): Long = (approxSizeBytes * 125) / 100

    private fun File.sha256Hex(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "SublyModelRegistry"
    }
}

private val WhisperModel.id: String
    get() = when (this) {
        WhisperModel.TINY_Q5_1 -> "tiny-q5_1"
        WhisperModel.BASE_Q5_1 -> "base-q5_1"
        WhisperModel.SMALL_Q5_1 -> "small-q5_1"
    }

private val WhisperModel.displayName: String
    get() = when (this) {
        WhisperModel.TINY_Q5_1 -> "Tiny (q5_1)"
        WhisperModel.BASE_Q5_1 -> "Base (q5_1)"
        WhisperModel.SMALL_Q5_1 -> "Small (q5_1)"
    }
