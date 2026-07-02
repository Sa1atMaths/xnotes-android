package com.xnotes.platform

import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/** Measures text with the same layout pipeline the renderer draws with. */
class AndroidTextMeasurer : TextMeasurer {

    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect {
        val probe = text.ifEmpty { " " }
        val paint = AndroidText.textPaint(font)
        val layout = AndroidText.layout(probe, wrapWidth.toInt(), paint)
        // Width is fixed to the wrap width; height follows the text.
        return Rect(0.0, 0.0, wrapWidth, layout.height.toDouble())
    }

    override fun lineHeight(font: FontSpec): Double = AndroidText.lineHeight(font)

    override fun metrics(font: FontSpec): LineMetrics {
        val fm = AndroidText.textPaint(font).fontMetrics
        // Paint ascent is negative (above baseline); expose both as positive distances.
        return LineMetrics(ascent = -fm.ascent.toDouble(), descent = fm.descent.toDouble())
    }

    override fun advances(text: String, font: FontSpec): DoubleArray {
        if (text.isEmpty()) return DoubleArray(0)
        val widths = FloatArray(text.length)
        AndroidText.textPaint(font).getTextWidths(text, widths)
        return DoubleArray(text.length) { widths[it].toDouble() }
    }
}
