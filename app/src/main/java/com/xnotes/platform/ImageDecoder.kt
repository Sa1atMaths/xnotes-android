package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xnotes.core.pal.ImageSize

/**
 * Decodes encoded image bytes downsampled to a target box, so a large photo is never fully decoded
 * into memory. Shared by [AndroidImageCodec], the on-screen [AndroidRenderer] and PDF export: each
 * asks only for the pixels its destination needs. Stateless; safe to call from any thread.
 */
object ImageDecoder {

    /** Native pixel size of [bytes] (bounds-only decode), or null if unreadable. */
    fun probe(bytes: ByteArray): ImageSize? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) ImageSize(o.outWidth, o.outHeight) else null
    }

    /** Decode [bytes] no larger than [maxWidth]×[maxHeight] (aspect kept), or null on failure. */
    fun decodeSampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        val mw = maxWidth.coerceAtLeast(1)
        val mh = maxHeight.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sw, sh, mw, mh)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width <= mw && bmp.height <= mh) return bmp
        // inSampleSize only halves, so a result can still exceed the box: scale the rest of the way.
        val scale = minOf(mw.toDouble() / bmp.width, mh.toDouble() / bmp.height)
        val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val th = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** Largest power-of-two sample step that keeps the decoded size at or above the requested box. */
    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var ss = 1
        while (w / (ss * 2) >= reqW && h / (ss * 2) >= reqH) ss *= 2
        return ss
    }
}
