package com.xnotes.core.model

import java.util.concurrent.atomic.AtomicLong

/**
 * The immutable source of an [ImageItem]: the original encoded image [bytes] plus its native pixel
 * size. Pixels are decoded on demand by the renderer at the resolution each draw needs (small for
 * the page cache, sharp for a deep zoom, full for export) so a large photo never sits fully decoded
 * in memory. Shared freely between copies because the bytes never change; [id] is a stable per-source
 * key for any decode cache.
 */
class ImageData(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    val id: Long = nextId.incrementAndGet()

    companion object {
        private val nextId = AtomicLong(0L)
    }
}
