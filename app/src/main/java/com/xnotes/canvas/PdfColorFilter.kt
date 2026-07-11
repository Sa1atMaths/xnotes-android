package com.xnotes.canvas

/**
 * Composes the View menu's PDF colour filters (CSS filter semantics) into one 4x5 colour
 * matrix in android.graphics.ColorMatrix layout: row-major, offsets in the fifth column,
 * channel range 0..255. Pure math so it is JVM-testable; the platform layer wraps the
 * result in a real ColorMatrix.
 */
object PdfColorFilter {

    fun isIdentity(contrast: Int, invert: Int, brightness: Int, sepia: Int): Boolean =
        contrast == 100 && invert == 0 && brightness == 100 && sepia == 0

    /** The composed matrix, applying contrast, then invert, then brightness, then sepia. */
    fun matrix(contrast: Int, invert: Int, brightness: Int, sepia: Int): FloatArray {
        var m = IDENTITY
        if (contrast != 100) m = concat(contrastMatrix(contrast / 100f), m)
        if (invert != 0) m = concat(invertMatrix(invert / 100f), m)
        if (brightness != 100) m = concat(brightnessMatrix(brightness / 100f), m)
        if (sepia != 0) m = concat(sepiaMatrix(sepia / 100f), m)
        return m
    }

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** CSS contrast(c): scale each channel about mid-grey. */
    private fun contrastMatrix(c: Float): FloatArray {
        val t = 127.5f * (1f - c)
        return floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** CSS invert(i): each channel interpolates toward its complement; i = 1 is a full invert. */
    private fun invertMatrix(i: Float): FloatArray {
        val s = 1f - 2f * i
        val t = 255f * i
        return floatArrayOf(
            s, 0f, 0f, 0f, t,
            0f, s, 0f, 0f, t,
            0f, 0f, s, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** CSS brightness(b): linear channel scale. */
    private fun brightnessMatrix(b: Float): FloatArray = floatArrayOf(
        b, 0f, 0f, 0f, 0f,
        0f, b, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** CSS sepia(s): the filter-effects sepia matrix interpolated with identity. */
    private fun sepiaMatrix(s: Float): FloatArray = floatArrayOf(
        1f - 0.607f * s, 0.769f * s, 0.189f * s, 0f, 0f,
        0.349f * s, 1f - 0.314f * s, 0.168f * s, 0f, 0f,
        0.272f * s, 0.534f * s, 1f - 0.869f * s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** a ∘ b: apply [b] first, then [a] (android ColorMatrix setConcat order). */
    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0 until 4) {
            val ar = r * 5
            for (c in 0 until 4) {
                var v = 0f
                for (k in 0 until 4) v += a[ar + k] * b[k * 5 + c]
                out[ar + c] = v
            }
            var off = a[ar + 4]
            for (k in 0 until 4) off += a[ar + k] * b[k * 5 + 4]
            out[ar + 4] = off
        }
        return out
    }
}

/**
 * The colour treatment a PDF page is rendered through: an optional page-wide matrix, and
 * whether embedded images are re-stamped over the filtered page (the "don't invert images"
 * preference) — [imageMatrix] is the same chain minus the invert stage, null when the
 * stamped pixels should stay raw originals.
 */
class PdfPageFilter private constructor(
    val pageMatrix: FloatArray?,
    val stampImages: Boolean,
    val imageMatrix: FloatArray?,
) {
    companion object {
        val NONE = PdfPageFilter(null, false, null)

        fun of(contrast: Int, invert: Int, brightness: Int, sepia: Int, keepImages: Boolean): PdfPageFilter {
            if (PdfColorFilter.isIdentity(contrast, invert, brightness, sepia)) return NONE
            val page = PdfColorFilter.matrix(contrast, invert, brightness, sepia)
            val stamp = keepImages && invert != 0
            val image = if (stamp && !PdfColorFilter.isIdentity(contrast, 0, brightness, sepia)) {
                PdfColorFilter.matrix(contrast, 0, brightness, sepia)
            } else {
                null
            }
            return PdfPageFilter(page, stamp, image)
        }
    }
}
