//package com.helios.subly.sdk.data.vision
//
//import android.graphics.Bitmap
//import android.util.Log
//import com.google.mlkit.vision.common.InputImage
//import com.google.mlkit.vision.text.TextRecognition
//import com.google.mlkit.vision.text.TextRecognizer
//import com.google.mlkit.vision.text.latin.TextRecognizerOptions
//import com.helios.subly.sdk.internal.utils.await
//import com.helios.subly.sdk.domain.model.VisionFrame
//import java.nio.ByteBuffer
//import androidx.core.graphics.createBitmap
//
///**
// * ML Kit Latin-script text recognizer.
// *
// * v1 target languages (en/vi/es) are all Latin-script, so the Latin-only
// * recogniser keeps APK size and inference cost down. CJK/Devanagari recognisers
// * can be swapped in later by injecting a different [OcrRecognizer].
// */
//internal class MlKitOcrRecognizer(
//    private val recognizer: TextRecognizer =
//        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
//) : OcrRecognizer {
//
//    override suspend fun recognize(frame: VisionFrame): String {
//        val bitmap = frame.toBitmap() ?: return ""
//        return try {
//            val image = InputImage.fromBitmap(bitmap, /* rotationDegrees= */ 0)
//            runCatching {
//                recognizer.process(image).await().text.trim()
//            }.getOrElse {
//                Log.w(TAG, "ML Kit text recognition failed", it)
//                ""
//            }
//        } finally {
//            bitmap.recycle()
//        }
//    }
//
//    override fun close() {
//        runCatching { recognizer.close() }
//    }
//
//    /**
//     * Converts an RGBA8888 [VisionFrame] to an ARGB_8888 Bitmap. When the
//     * source row stride exceeds `width * 4` (common for `ImageReader`), the
//     * pixels are packed into a contiguous buffer first.
//     */
//    private fun VisionFrame.toBitmap(): Bitmap? {
//        if (pixels.isEmpty() || width <= 0 || height <= 0) return null
//        val rowBytes = width * 4
//        val packed = if (rowStrideBytes == rowBytes) {
//            pixels
//        } else {
//            ByteArray(rowBytes * height).also { dst ->
//                for (y in 0 until height) {
//                    System.arraycopy(pixels, y * rowStrideBytes, dst, y * rowBytes, rowBytes)
//                }
//            }
//        }
//        val bmp = createBitmap(width, height)
//        return try {
//            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
//            bmp
//        } catch (t: Throwable) {
//            Log.w(TAG, "Bitmap copy failed", t)
//            bmp.recycle()
//            null
//        }
//    }
//
//    private companion object {
//        const val TAG = "DUY"
//    }
//}
