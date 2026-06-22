package com.helios.subly.asr.sherpa

import android.content.Context
import android.util.Log
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
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
 *  3. Download each `downloadFiles` entry from `downloadBaseUrl`.
 */
internal fun interface SherpaOnnxModelLoaderFactory {
    fun create(context: Context): SherpaOnnxModelLoader

    companion object {
        /** Factory backed by the default OkHttp downloader. */
        val Default: SherpaOnnxModelLoaderFactory = of(OkHttpModelDownloader())

        /** Factory that builds loaders around a caller-supplied [downloader]. */
        fun of(downloader: ModelDownloader): SherpaOnnxModelLoaderFactory =
            SherpaOnnxModelLoaderFactory { context -> SherpaOnnxModelLoader(context, downloader) }
    }
}

internal open class SherpaOnnxModelLoader(
    private val context: Context,
    private val downloader: ModelDownloader = OkHttpModelDownloader(),
) {

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
     * Makes [model] available on disk, emitting progress in [0.0, 1.0].
     *
     * Priority: already-present (instant 1.0) -> unpack app-bundled assets ->
     * download [SherpaOnnxModel.downloadFiles]. Completes without reaching
     * 1.0 only if no source is available; the caller MUST verify with
     * [isReady] afterwards (a finished-but-not-ready flow means "couldn't
     * provision"). Collect on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun provisionWithProgress(model: SherpaOnnxModel): Flow<Float> = flow {
        val target = File(rootDir, model.dirName)
        if (hasRequiredFiles(model, target)) {
            emit(1f)
            return@flow
        }

        // Prefer an app-bundled asset copy when present (offline / no network).
        val assetDir = "$ASSETS_DIR/${model.dirName}"
        val bundled = runCatching { context.assets.list(assetDir).orEmpty() }.getOrDefault(emptyArray())
        if (bundled.isNotEmpty()) {
            emitAll(extractWithProgress(model))
            return@flow
        }

        if (model.downloadFiles.isEmpty() || model.downloadBaseUrl.isEmpty()) {
            Log.w(TAG, "No bundled assets and no download source for ${model.dirName}.")
            return@flow
        }

        // Download each file, weighting progress equally across the file count.
        target.mkdirs()
        val total = model.downloadFiles.size
        model.downloadFiles.forEachIndexed { index, fileName ->
            val dest = File(target, fileName)
            val url = "${model.downloadBaseUrl.trimEnd('/')}/$fileName?download=true"
            downloader.downloadModel(url, dest).collect { fileProgress ->
                emit(((index + fileProgress) / total).coerceIn(0f, 1f))
            }
        }
        Log.i(TAG, "Downloaded ${model.dirName} -> ${target.absolutePath}")
        emit(1f)
    }

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
        const val TAG = "SherpaOnnxModelLoader"
        const val MODELS_DIR = "sherpa-onnx"
        const val ASSETS_DIR = "sherpa-onnx"
    }
}
