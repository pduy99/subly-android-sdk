package com.helios.subly.sdk.data.vision

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * `MediaProjection` -> `VirtualDisplay` -> `ImageReader` implementation of
 * [VirtualDisplayDataSource].
 *
 * The Android-side push callback is bridged into a suspending pull via a
 * conflated [Channel]: the listener thread drops a frame as soon as it
 * arrives sooner than the throttle interval (no pixel copy), and any frame
 * that does get copied is offered to the channel with `DROP_OLDEST` so a
 * slow OCR consumer doesn't stall the listener thread.
 *
 * The native long edge is downscaled to [maxLongEdgePx] (default 1280 px)
 * to keep ML Kit inference latency bounded; aspect ratio is preserved.
 */
internal class ImageReaderDataSource(
    private val context: Context,
    private val maxLongEdgePx: Int = 1280,
) : VirtualDisplayDataSource {

    @Volatile private var activeMediaProjection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var handlerThread: HandlerThread? = null
    @Volatile private var channel: Channel<RawVisionFrame>? = null

    @Synchronized
    override fun open(mediaProjection: MediaProjection, targetFps: Int) {
        check(imageReader == null) { "ImageReaderDataSource already open" }

        val (w, h, dpi) = screenMetrics()
        val (outW, outH) = scaleToMax(w, h, maxLongEdgePx)

        val thread = HandlerThread("SublyVisionCapture").apply { start() }
        val handler = Handler(thread.looper)
        // maxImages=2: one in-flight for the listener, one queued by the
        // producer. Larger queues just stall the producer when OCR can't
        // keep up; we'd rather drop frames via acquireLatestImage().
        val reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
        
        if (activeMediaProjection === mediaProjection && virtualDisplay != null) {
            // Android 14+ throws SecurityException if createVirtualDisplay is called 
            // multiple times on the same MediaProjection instance. Reuse the existing one.
            virtualDisplay!!.resize(outW, outH, dpi)
            virtualDisplay!!.surface = reader.surface
        } else {
            virtualDisplay?.release()
            val display = mediaProjection.createVirtualDisplay(
                "SublyVisionVD",
                outW, outH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                /* callback = */ null,
                handler,
            )
            virtualDisplay = display
            activeMediaProjection = mediaProjection
        }
        val ch = Channel<RawVisionFrame>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        handlerThread = thread
        imageReader = reader
        channel = ch

        val frameIntervalMs = if (targetFps > 0) 1000L / targetFps else 0L
        var lastEmitMs = 0L

        reader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            if (frameIntervalMs > 0 && now - lastEmitMs < frameIntervalMs) {
                // Discard without copying so the buffer pool turns over.
                runCatching { r.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val frame = image.toRawFrame() ?: return@setOnImageAvailableListener
                // trySend never suspends; with DROP_OLDEST the older queued
                // frame is replaced when OCR can't keep up.
                ch.trySend(frame)
                lastEmitMs = now
            } finally {
                runCatching { image.close() }
            }
        }, handler)
    }

    override suspend fun readFrame(): RawVisionFrame? {
        val ch = channel ?: return null
        return ch.receiveCatching().getOrNull()
    }

    @Synchronized
    override fun close() {
        runCatching { channel?.close() }
        channel = null
        
        // Pause the virtual display instead of releasing it to avoid SecurityException 
        // if this MediaProjection is reused later.
        runCatching { virtualDisplay?.surface = null }
        
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
    }

    private fun Image.toRawFrame(): RawVisionFrame? {
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        // ImageReader RGBA_8888 always returns pixelStride == 4; bail loudly
        // if a vendor deviates rather than corrupting the bitmap silently.
        if (pixelStride != 4) {
            Log.w(TAG, "Unexpected RGBA pixelStride=$pixelStride; skipping frame")
            return null
        }
        val buffer = plane.buffer
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return RawVisionFrame(
            pixels = out,
            width = width,
            height = height,
            rowStrideBytes = plane.rowStride,
        )
    }

    private data class Metrics(val width: Int, val height: Int, val densityDpi: Int)

    private fun screenMetrics(): Metrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // WindowMetrics gives the full display bounds incl. system bars, which
        // is what MediaProjection actually mirrors.
        val bounds = wm.maximumWindowMetrics.bounds
        val dpi = context.resources.configuration.densityDpi.takeIf { it > 0 }
            ?: DisplayMetrics.DENSITY_DEFAULT
        return Metrics(bounds.width(), bounds.height(), dpi)
    }

    private fun scaleToMax(w: Int, h: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(w, h)
        if (longEdge <= maxLongEdge) return w to h
        val scale = maxLongEdge.toFloat() / longEdge
        // ImageReader prefers even dimensions; floor to nearest even.
        val nw = ((w * scale).toInt() / 2) * 2
        val nh = ((h * scale).toInt() / 2) * 2
        return nw.coerceAtLeast(2) to nh.coerceAtLeast(2)
    }

    private companion object {
        const val TAG = "DUY"
    }
}
