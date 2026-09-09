package com.helios.subly.translator.litertlm

import android.content.Context
import android.util.Log
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Resolves a [LiteRtLmModel] to a file under `filesDir/litertlm/`.
 *
 * The one thing this does that the ASR loaders do not is check free space
 * first. At ~1.6 GB a failed download is not a retry annoyance, it is
 * minutes of a user's data plan spent filling a disk that was never going to
 * hold the file.
 */
internal open class LiteRtLmModelLoader(
    private val context: Context,
    private val downloader: ModelDownloader = OkHttpModelDownloader(),
) {

    private val rootDir: File by lazy { File(context.filesDir, MODELS_DIR).apply { mkdirs() } }

    open fun modelFile(model: LiteRtLmModel): File = File(rootDir, model.fileName)

    /**
     * A partial download would leave a short file that the engine cannot
     * load, so size is part of readiness, not just existence. The downloader
     * writes to a temp file and renames on completion, so a short file here
     * means a pre-seeded or hand-copied one.
     */
    open fun isReady(model: LiteRtLmModel): Boolean =
        modelFile(model).let { it.isFile && it.length() >= model.approxSizeBytes }

    /** @return null when there is room, or a description of the shortfall. */
    open fun insufficientSpace(model: LiteRtLmModel): String? {
        val usable = rootDir.usableSpace
        // The downloader stages into a sibling temp file before renaming, so
        // the peak requirement is one copy, plus a margin so provisioning
        // does not leave the device with a full disk.
        val needed = model.approxSizeBytes + SPACE_MARGIN_BYTES
        if (usable <= 0L || usable >= needed) return null
        return "needs ${needed / MB} MB free, device has ${usable / MB} MB"
    }

    /**
     * Makes [model] available on disk, emitting progress in `0f..1f`.
     *
     * Completes without reaching 1.0 when the download failed; callers MUST
     * verify with [isReady] rather than trusting completion.
     */
    open fun provisionWithProgress(model: LiteRtLmModel): Flow<Float> = flow {
        if (isReady(model)) {
            emit(1f)
            return@flow
        }
        Log.i(TAG, "Downloading ${model.fileName} (${model.approxSizeBytes / MB} MB)")
        downloader.downloadModel(model.downloadUrl, modelFile(model)).collect { emit(it) }
    }

    private companion object {
        const val TAG = "LiteRtLmModelLoader"
        const val MODELS_DIR = "litertlm"
        const val MB = 1024L * 1024L
        const val SPACE_MARGIN_BYTES = 256L * MB
    }
}
