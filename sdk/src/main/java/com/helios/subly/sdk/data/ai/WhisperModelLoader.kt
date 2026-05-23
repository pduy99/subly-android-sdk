package com.helios.subly.sdk.data.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves a [WhisperModel] to an on-disk file path suitable for
 * `whisper_init_from_file_with_params`.
 *
 * Resolution order:
 *  1. Already extracted into `filesDir/models/<assetName>` (downloaded or
 *     previously unpacked) -> reuse.
 *  2. Bundled in `assets/models/<assetName>` -> unpack once to `filesDir/models/`
 *     so JNI can mmap it.
 *  3. Otherwise -> `null` (caller should report "model not available" and
 *     prompt download).
 *
 * Unpacking is required because whisper.cpp reads via `fopen`; APK assets are
 * not real files on disk.
 */
/**
 * Builds a [WhisperModelLoader] bound to an Android [Context]. Lifted to an
 * interface so [WhisperTranscriber] can be unit-tested with a fake without
 * passing function references.
 */
internal interface WhisperModelLoaderFactory {
    fun create(context: Context): WhisperModelLoader

    companion object Default : WhisperModelLoaderFactory {
        override fun create(context: Context): WhisperModelLoader = WhisperModelLoader(context)
    }
}

internal open class WhisperModelLoader(private val context: Context) {

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    /** Returns the absolute path to a ready-to-load model, or `null`. */
    open fun resolve(model: WhisperModel = WhisperModel.Default): String? {
        val target = File(modelsDir, model.assetName)
        if (target.exists() && target.length() > 0) return target.absolutePath

        if (model.bundledInAssets && unpackFromAssets(model, target)) {
            return target.absolutePath
        }
        Log.w(TAG, "Model ${model.assetName} not available (not bundled, not downloaded).")
        return null
    }

    /** Target file the language-downloader (Consumer Module C) writes into. */
    fun downloadTarget(model: WhisperModel): File = File(modelsDir, model.assetName)

    private fun unpackFromAssets(model: WhisperModel, target: File): Boolean {
        val assetPath = "$ASSETS_MODELS_DIR/${model.assetName}"
        return runCatching {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Unpacked ${model.assetName} -> ${target.absolutePath} (${target.length()} bytes)")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Failed to unpack $assetPath: ${error.message}")
            target.delete()
            false
        }
    }

    private companion object {
        const val TAG = "SublyModelLoader"
        const val MODELS_DIR = "models"
        const val ASSETS_MODELS_DIR = "models"
    }
}
