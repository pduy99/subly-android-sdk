package com.helios.subly.asr.vad

import android.content.Context
import android.util.Log
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Resolves [SileroVadModel] to a file on disk.
 *
 * An interface rather than a concrete class so [SpeechGate]'s decision logic
 * can be unit-tested on the JVM, where there is no `Context` to build the
 * real loader against.
 */
internal interface VadModelLoader {

    fun modelFile(): File

    fun isReady(): Boolean

    /**
     * Makes the checkpoint available on disk, emitting progress in `0f..1f`.
     *
     * Completes without reaching 1.0 when the download failed; callers MUST
     * verify with [isReady] rather than trusting completion.
     */
    fun provisionWithProgress(): Flow<Float>
}

/**
 * Downloads the checkpoint into `filesDir/vad/`.
 *
 * A single small file, so the resolution order is just "already there ->
 * download". Unlike the ASR loaders there is no app-bundled-asset path: at
 * ~0.6 MB the download is cheap enough that bundling would cost more APK
 * than it saves, and an app that wants it offline can pre-seed the file.
 */
internal class DownloadingVadModelLoader(
    context: Context,
    private val downloader: ModelDownloader = OkHttpModelDownloader(),
) : VadModelLoader {

    private val rootDir: File by lazy { File(context.filesDir, MODELS_DIR).apply { mkdirs() } }

    override fun modelFile(): File = File(rootDir, SileroVadModel.FILE_NAME)

    override fun isReady(): Boolean = modelFile().let { it.isFile && it.length() > 0L }

    override fun provisionWithProgress(): Flow<Float> = flow {
        if (isReady()) {
            emit(1f)
            return@flow
        }
        downloader.downloadModel(SileroVadModel.DOWNLOAD_URL, modelFile()).collect { emit(it) }
        Log.i(TAG, "Downloaded ${SileroVadModel.FILE_NAME} -> ${modelFile().absolutePath}")
    }

    private companion object {
        const val TAG = "VadModelLoader"
        const val MODELS_DIR = "vad"
    }
}
