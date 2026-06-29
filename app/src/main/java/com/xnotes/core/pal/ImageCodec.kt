package com.xnotes.core.pal

/** Native pixel dimensions of an encoded image, returned by [ImageCodec.probeFile]. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Reads an encoded image's size and encodes presentation frames (spec 01 §3). Inserted-image pixels
 * are never decoded through here: they live as files and the renderer decodes them on demand (see
 * [com.xnotes.platform.ImageDecoder]), so a note full of large images never fills the heap. [probeFile]
 * reads a file's native size without decoding the pixels.
 */
interface ImageCodec {
    /** Native pixel size of the image at [path] without decoding the pixels, or null if unreadable. */
    fun probeFile(path: String): ImageSize?

    /** JPEG-encode [surface] (presentation frames); [quality] is 0..1. */
    fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray
}
