package com.xnotes.platform

import android.graphics.Bitmap
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.pal.RasterSurface
import java.io.ByteArrayOutputStream

/** Reads image sizes and encodes presentation frames via the Android framework (spec 01 §3).
 *  Inserted-image decoding goes through [ImageDecoder] (file-backed, on demand), not this codec. */
class AndroidImageCodec : ImageCodec {

    override fun probeFile(path: String): ImageSize? = ImageDecoder.probeFile(path)

    override fun encodeJpeg(surface: RasterSurface, quality: Double): ByteArray {
        val bmp = (surface as AndroidRasterSurface).bitmap
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(1, 100), out)
            out.toByteArray()
        }
    }
}
