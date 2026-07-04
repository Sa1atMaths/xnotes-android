package com.xnotes.platform

import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec

/**
 * Shared text layout so measuring and drawing produce identical results
 * (spec 01 §12). A "point size" is converted to page-space pixels at the
 * document authoring DPI (150), so 13pt renders as true 13pt on a 150-DPI page.
 */
object AndroidText {
    /** points -> page pixels at 150 DPI (1pt = 1/72 inch). */
    const val POINTS_TO_PX = 150f / 72f

    // The four abstract faces, resolved to platform typefaces. Held as immutable
    // vals (no shared mutable cache) since paints are built on background cache threads.
    private val sans: Typeface = Typeface.SANS_SERIF
    private val serif: Typeface = Typeface.SERIF
    private val mono: Typeface = Typeface.MONOSPACE
    private val hand: Typeface = Typeface.create("cursive", Typeface.NORMAL)

    private fun base(face: FontFace): Typeface = when (face) {
        FontFace.SERIF -> serif
        FontFace.MONO -> mono
        FontFace.HAND -> hand
        else -> sans
    }

    fun textPaint(font: FontSpec, argb: Int = 0xFF000000.toInt()): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            val face = base(font.face)
            val style = when {
                font.bold && font.italic -> Typeface.BOLD_ITALIC
                font.bold -> Typeface.BOLD
                font.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            // Typeface.create(base, style) is thread-safe and framework-cached.
            typeface = if (style == Typeface.NORMAL) face else Typeface.create(face, style)
            textSize = (font.pointSize * POINTS_TO_PX).toFloat()
            color = argb
        }

    fun layout(text: CharSequence, widthPx: Int, paint: TextPaint): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    fun lineHeight(font: FontSpec): Double {
        val fm = textPaint(font).fontMetrics
        return (fm.descent - fm.ascent).toDouble()
    }
}
