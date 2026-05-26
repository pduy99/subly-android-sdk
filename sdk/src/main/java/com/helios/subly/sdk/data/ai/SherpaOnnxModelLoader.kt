package com.helios.subly.sdk.data.ai

import android.content.Context
import android.util.Log
import java.io.File

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
    @Suppress("unused")
    fun downloadTarget(model: SherpaOnnxModel): File = File(rootDir, model.dirName)

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
        const val TAG = "SublySherpaOnnxLoader"
        const val MODELS_DIR = "sherpa-onnx"
        const val ASSETS_DIR = "sherpa-onnx"
    }
}
