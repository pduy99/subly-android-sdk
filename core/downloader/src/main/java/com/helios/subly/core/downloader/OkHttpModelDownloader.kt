package com.helios.subly.core.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class OkHttpModelDownloader(
    private val client: OkHttpClient = OkHttpClient()
) : ModelDownloader {

    override fun downloadModel(url: String, destFile: File): Flow<Float> = flow {
        if (destFile.exists() && destFile.length() > 0L) {
            emit(1f)
            return@flow
        }

        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength().coerceAtLeast(1L)
            
            // Create a temporary file to prevent partial downloads from being perceived as complete
            val tempFile = File(destFile.absolutePath + ".tmp")
            
            body.source().use { source ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesCopied = 0L
                    var bytes = source.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        emit((bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f))
                        bytes = source.read(buffer)
                    }
                }
            }
            
            // Rename temp to dest
            if (destFile.exists()) {
                destFile.delete()
            }
            tempFile.renameTo(destFile)
            emit(1f)
        } catch (e: Exception) {
            if (e is CancellationException) {
                call.cancel()
            }
            // Cleanup partial file if error occurs
            val tempFile = File(destFile.absolutePath + ".tmp")
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
