package com.helios.subly.asr.sherpa

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow


/**
 * Resolves a [SherpaOnnxModel] to an on-disk directory containing the ONNX
 * checkpoint files. Unlike whisper this is a directory (not a single file)
 * because sherpa-onnx ships encoder/decoder/joiner + tokens as separate
 * artifacts.
 *
 * Resolution order:
 *  1. `filesDir/sherpa-onnx/<dirName>/` already populated (downloaded or
 *     previously extracted) -> reuse.
 *  2. Bundled under `assets/sherpa-onnx/<dirName>/` -> unpack once.
 *  3. Otherwise `null` -> caller should prompt download.
 */
internal interface SherpaOnnxModelLoaderFactory {
    fun create(context: Context): SherpaOnnxModelLoader

    companion object Default : SherpaOnnxModelLoaderFactory {
        override fun create(context: Context): SherpaOnnxModelLoader =
            SherpaOnnxModelLoader(context)
    }
}

internal open class SherpaOnnxModelLoader(private val context: Context) {

    private val rootDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    /** Absolute path to the model directory, or `null` if not ready. */
    @Suppress("unused")
    open fun resolve(model: SherpaOnnxModel = SherpaOnnxModel.Default): String? {
        val target = File(rootDir, model.dirName)
        if (hasRequiredFiles(model, target)) return target.absolutePath

        if (unpackFromAssets(model, target) && hasRequiredFiles(model, target)) {
            return target.absolutePath
        }
        Log.w(TAG, "Sherpa-onnx model '${model.dirName}' not available.")
        return null
    }

    /** Target directory the language-downloader writes into. */
    fun downloadTarget(model: SherpaOnnxModel): File = File(rootDir, model.dirName)

    /** Returns true if all required files for [model] are already on-disk. */
    @Suppress("unused")
    fun isReady(model: SherpaOnnxModel): Boolean =
        hasRequiredFiles(model, File(rootDir, model.dirName))

    /**
     * Extracts the model asset bundle to [downloadTarget] with progress.
     *
     * Emits values in [0.0, 1.0] as bytes are copied. Emits 1.0 immediately
     * if the model is already extracted. The returned flow runs on the
     * caller's dispatcher — collect on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun extractWithProgress(model: SherpaOnnxModel): Flow<Float> = flow {
        val target = File(rootDir, model.dirName)
        if (hasRequiredFiles(model, target)) {
            emit(1f)
            return@flow
        }

        val assetDir = "$ASSETS_DIR/${model.dirName}"
        val children = context.assets.list(assetDir).orEmpty()
        if (children.isEmpty()) {
            Log.w(TAG, "No assets found at $assetDir")
            return@flow
        }

        // Sum declared sizes for accurate progress. Fall back to approxSizeBytes.
        val totalBytes = children.sumOf { name ->
            runCatching {
                context.assets.openFd("$assetDir/$name").declaredLength
            }.getOrDefault(0L)
        }.takeIf { it > 0L } ?: model.approxSizeBytes.coerceAtLeast(1L)

        target.mkdirs()
        var copiedBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        children.forEach { name ->
            context.assets.open("$assetDir/$name").use { input ->
                File(target, name).outputStream().use { out ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        copiedBytes += read
                        emit((copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
        }
        Log.i(TAG, "Extracted ${model.dirName} -> ${target.absolutePath}")
        emit(1f)
    }

    private fun hasRequiredFiles(model: SherpaOnnxModel, dir: File): Boolean {
        if (!dir.isDirectory) return false
        val filesInDir = dir.listFiles()?.map { it.name }.orEmpty()
        return model.assetPatterns.all { pattern ->
            filesInDir.any { fileName -> pattern.matches(fileName) }
        }
    }

    private fun unpackFromAssets(model: SherpaOnnxModel, target: File): Boolean {
        val assetDir = "$ASSETS_DIR/${model.dirName}"
        return runCatching {
            val children = context.assets.list(assetDir).orEmpty()
            if (children.isEmpty()) return@runCatching false
            target.mkdirs()
            children.forEach { name ->
                context.assets.open("$assetDir/$name").use { input ->
                    File(target, name).outputStream().use { out -> input.copyTo(out) }
                }
            }
            Log.i(TAG, "Unpacked sherpa-onnx model -> ${target.absolutePath}")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to unpack $assetDir: ${error.message}")
            false
        }
    }

    private companion object {
        const val TAG = "DUY"
        const val MODELS_DIR = "sherpa-onnx"
        const val ASSETS_DIR = "sherpa-onnx"
    }
}
