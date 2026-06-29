package com.xnotes.core.pal

/** Native pixel dimensions of an encoded image, returned by [ImageCodec.probe]. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Moves bitmaps between files/bytes and [RasterSurface]s (spec 01 §3). Decoders
 * report failure as `null`. [probe] reads the native size without decoding pixels and
 * [decodeSampled] decodes downsampled to a target box, so a large source is never fully
 * decoded into memory. PNG encoding embeds images in the `.xnote` bundle; JPEG encoding
 * produces presentation frames.
 */
interface ImageCodec {
    fun decode(bytes: ByteArray): RasterSurface?
    fun decodePath(path: String): RasterSurface?

    /** Native pixel size of [bytes] without decoding the pixels (bounds only), or null if unreadable. */
    fun probe(bytes: ByteArray): ImageSize?

    /** Decode [bytes] downsampled to fit [maxWidth]×[maxHeight] (aspect kept), or null on failure. */
    fun decodeSampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): RasterSurface?

    fun encodePng(surface: RasterSurface): ByteArray
    fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray
    fun encodeWebp(surface: RasterSurface, quality: Double): ByteArray? = null
}
