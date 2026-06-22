package com.helios.subly.asr.vosk

import android.content.Context
import android.util.Log
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.zip.ZipFile

/**
 * Resolves a [VoskModels] entry to an on-disk directory containing the unpacked
 * Kaldi model (`am/`, `conf/`, `graph/`, `ivector/`).
 *
 * Vosk distributes each model as a single `.zip` whose entries are nested under
 * a top-level folder named after the model
 * (`vosk-model-small-en-us-0.15/am/final.mdl`, ...). This loader downloads the
 * zip and unpacks it, *flattening* that wrapper folder so the model files land
 * directly in `filesDir/vosk/<dirName>/` — the layout `org.vosk.Model` expects.
 */
internal fun interface VoskModelLoaderFactory {
    fun create(context: Context): VoskModelLoader

    companion object {
        val Default: VoskModelLoaderFactory = of(OkHttpModelDownloader())

        fun of(downloader: ModelDownloader): VoskModelLoaderFactory =
            VoskModelLoaderFactory { context -> VoskModelLoader(context, downloader) }
    }
}

internal open class VoskModelLoader(
    private val context: Context,
    private val downloader: ModelDownloader = OkHttpModelDownloader(),
) {

    private val rootDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    /** Directory the unpacked model lives in (whether or not it's present yet). */
    fun modelDir(model: VoskModels): File = File(rootDir, model.dirName)

    /** True if [model] is unpacked and the core Kaldi files are present. */
    fun isReady(model: VoskModels): Boolean = hasRequiredFiles(modelDir(model))

    /**
     * Makes [model] available on disk, emitting progress in `[0f, 1f]`.
     *
     * Phases: download the zip (0 -> [DOWNLOAD_FRACTION]) then unpack it
     * ([DOWNLOAD_FRACTION] -> 1.0). Already-present models emit `1f` instantly.
     *
     * Completes WITHOUT reaching `1f` only if provisioning failed to produce the
     * required files; callers MUST verify with [isReady] afterwards (a
     * finished-but-not-ready flow means "couldn't provision"). Collect on
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun provisionWithProgress(model: VoskModels): Flow<Float> = flow {
        val target = modelDir(model)
        if (hasRequiredFiles(target)) {
            emit(1f)
            return@flow
        }

        // Clear any partial/corrupt previous attempt before re-provisioning.
        if (target.exists()) target.deleteRecursively()

        val zipFile = File(rootDir, "${model.dirName}.zip")
        runCatching { if (zipFile.exists()) zipFile.delete() }

        // Phase 1: download the zip.
        downloader.downloadModel(model.zipUrl, zipFile).collect { p ->
            emit((p * DOWNLOAD_FRACTION).coerceIn(0f, 1f))
        }
        if (!zipFile.exists() || zipFile.length() == 0L) {
            Log.w(TAG, "Vosk zip download produced no file for ${model.dirName}.")
            return@flow
        }

        // Phase 2: unpack + flatten the wrapper folder.
        runCatching {
            unzipFlattened(zipFile, target) { frac ->
                emit((DOWNLOAD_FRACTION + frac * (1f - DOWNLOAD_FRACTION)).coerceIn(0f, 1f))
            }
        }.onFailure { Log.w(TAG, "Unpack failed for ${model.dirName}: ${it.message}") }

        // The zip is large; don't keep it around once unpacked.
        runCatching { zipFile.delete() }

        if (hasRequiredFiles(target)) {
            Log.i(TAG, "Vosk model ready -> ${target.absolutePath}")
            emit(1f)
        } else {
            Log.w(TAG, "Vosk model ${model.dirName} unpacked but required files missing.")
        }
    }

    /**
     * Extracts [zip] into [target], dropping the leading path segment of every
     * entry (Vosk wraps the model in a `<model-name>/` folder). Reports progress
     * as a fraction of total uncompressed bytes.
     */
    private suspend fun unzipFlattened(
        zip: File,
        target: File,
        onProgress: suspend (Float) -> Unit,
    ) {
        target.mkdirs()
        val canonicalTarget = target.canonicalPath + File.separator
        ZipFile(zip).use { zf ->
            val totalBytes = zf.entries().asSequence()
                .filter { !it.isDirectory }
                .sumOf { it.size.coerceAtLeast(0L) }
                .coerceAtLeast(1L)

            var copied = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isEmpty()) continue // the wrapper dir itself

                val outFile = File(target, relative)
                // Zip-slip guard: never let an entry escape the target dir.
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                    throw SecurityException("Blocked zip entry outside target: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    continue
                }
                outFile.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    outFile.outputStream().use { out ->
                        var read = input.read(buffer)
                        while (read != -1) {
                            out.write(buffer, 0, read)
                            copied += read
                            onProgress((copied.toFloat() / totalBytes).coerceIn(0f, 1f))
                            read = input.read(buffer)
                        }
                    }
                }
            }
        }
    }

    /**
     * Minimal validity check for an unpacked Vosk model: the nnet3 acoustic
     * model, its config, and a decoding graph (either the monolithic `HCLG.fst`
     * or the split lookahead `HCLr.fst`). `org.vosk.Model` validates the rest.
     */
    private fun hasRequiredFiles(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val hasAm = File(dir, "am/final.mdl").isFile
        val hasConf = File(dir, "conf/model.conf").isFile
        val hasGraph = File(dir, "graph/HCLG.fst").isFile || File(dir, "graph/HCLr.fst").isFile
        return hasAm && hasConf && hasGraph
    }

    private companion object {
        const val TAG = "VoskModelLoader"
        const val MODELS_DIR = "vosk"

        /** Share of total progress allotted to the download phase. */
        const val DOWNLOAD_FRACTION = 0.85f
    }
}
