package com.helios.subly.core.model

/**
 * One screen frame captured by the vision fallback pipeline (`VirtualDisplay` +
 * `ImageReader`). Held as raw RGBA8888 pixels for direct ML Kit ingestion.
 *
 * @property pixels Tightly packed RGBA8888 pixel data.
 * @property width Frame width in pixels.
 * @property height Frame height in pixels.
 * @property rowStrideBytes Stride between consecutive rows (may exceed `width * 4`).
 * @property timestampMs Monotonic wall-clock time the frame was read.
 */
data class VisionFrame(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionFrame) return false
        return width == other.width &&
            height == other.height &&
            rowStrideBytes == other.rowStrideBytes &&
            timestampMs == other.timestampMs &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rowStrideBytes
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
