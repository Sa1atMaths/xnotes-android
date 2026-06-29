package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xnotes.core.pal.ImageSize

/**
 * Decodes an encoded image file downsampled to a target box, so a large photo is never fully decoded
 * into memory and its bytes are never slurped into the heap. Shared by [AndroidImageCodec], the
 * on-screen [AndroidRenderer] and PDF export: each asks only for the pixels its destination needs.
 * Stateless; safe to call from any thread.
 */
object ImageDecoder {

    /** Native pixel size of the image at [path] (bounds-only decode), or null if unreadable. */
    fun probeFile(path: String): ImageSize? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) ImageSize(o.outWidth, o.outHeight) else null
    }

    /** Decode the image at [path] no larger than [maxWidth]×[maxHeight] (aspect kept), or null. */
    fun decodeSampledFile(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val mw = maxWidth.coerceAtLeast(1)
        val mh = maxHeight.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sw, sh, mw, mh)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
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
