package com.xnotes.core.text

import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextMeasurer

/** The concrete font a run resolves to: style overrides over the flow defaults; code is mono. */
fun resolveFont(flow: TextFlow, para: Paragraph, style: CharStyle): FontSpec = FontSpec(
    pointSize = style.sizePt ?: flow.defaultSizePt,
    face = if (para.codeLang != null || style.code) FontFace.MONO else flow.defaultFace,
    bold = style.bold,
    italic = style.italic,
)

/**
 * One wrapped line of a paragraph: characters [startChar, endChar) of its plain
 * text. Trailing spaces hang (they are inside the span but excluded from
 * [width]); [spaceCount] is the stretchable interior spaces justify distributes
 * into; [hardBroken] lines (mid-word) never justify.
 */
class BrokenLine(
    val startChar: Int,
    val endChar: Int,
    val width: Double,
    val ascent: Double,
    val descent: Double,
    val spaceCount: Int,
    val hardBroken: Boolean,
) {
    val height: Double get() = ascent + descent
}

/**
 * Lays the flow out: greedy word wrap per paragraph (cached by paragraph
 * revision and width), then pagination into per-page content rects. Everything
 * here is pure and deterministic against the [TextMeasurer].
 */
class FlowLayout(private val measurer: TextMeasurer) {

    // --- per-paragraph measurement (cached) ---

    /** Advances and per-run fonts/metrics for one paragraph at given flow defaults. */
    private class ParaShape(
        val text: String,
        val adv: DoubleArray,
        val runEnds: IntArray,
        val runMetrics: Array<LineMetrics>,
        val runFonts: Array<FontSpec>,
        val emptyMetrics: LineMetrics,
    )

    private class ShapeEntry(val rev: Int, val defaultsKey: Long, val shape: ParaShape)
    private class BreakEntry(val rev: Int, val defaultsKey: Long, val width: Double, val lines: List<BrokenLine>)

    private val shapes = HashMap<Paragraph, ShapeEntry>()
    private val breaks = HashMap<Paragraph, BreakEntry>()

    private fun defaultsKey(flow: TextFlow): Long =
        (flow.defaultFace.ordinal.toLong() shl 32) xor flow.defaultSizePt.toRawBits()

    private fun shapeOf(flow: TextFlow, para: Paragraph): ParaShape {
        val key = defaultsKey(flow)
        shapes[para]?.let { if (it.rev == para.rev && it.defaultsKey == key) return it.shape }
        val text = para.plainText()
        val adv = DoubleArray(text.length)
        val runEnds = IntArray(para.runs.size)
        val runMetrics = arrayOfNulls<LineMetrics>(para.runs.size)
        val runFonts = arrayOfNulls<FontSpec>(para.runs.size)
        var offset = 0
        for ((i, run) in para.runs.withIndex()) {
            val font = resolveFont(flow, para, run.style)
            val a = measurer.advances(run.text, font)
            a.copyInto(adv, offset)
            offset += run.text.length
            runEnds[i] = offset
            runFonts[i] = font
            runMetrics[i] = measurer.metrics(font)
        }
        val emptyFont = resolveFont(flow, para, CharStyle.DEFAULT)
        val shape = ParaShape(
            text, adv, runEnds,
            @Suppress("UNCHECKED_CAST") (runMetrics as Array<LineMetrics>),
            @Suppress("UNCHECKED_CAST") (runFonts as Array<FontSpec>),
            measurer.metrics(emptyFont),
        )
        shapes[para] = ShapeEntry(para.rev, key, shape)
        return shape
    }

    // --- line breaking ---

    /**
     * Greedy word wrap of [para] at [availWidth]: break opportunities sit after
     * space runs; a word wider than the line hard-breaks per character (always
     * placing at least one char so layout progresses). [fromChar] re-breaks a
     * paragraph remainder when it continues onto a page of different width.
     */
    fun breakLines(flow: TextFlow, para: Paragraph, availWidth: Double, fromChar: Int = 0): List<BrokenLine> {
        val key = defaultsKey(flow)
        if (fromChar == 0) {
            breaks[para]?.let {
                if (it.rev == para.rev && it.defaultsKey == key && it.width == availWidth) return it.lines
            }
        }
        val shape = shapeOf(flow, para)
        val lines = breakShape(shape, availWidth.coerceAtLeast(MIN_LINE_WIDTH), fromChar)
        if (fromChar == 0) breaks[para] = BreakEntry(para.rev, key, availWidth, lines)
        return lines
    }

    private fun breakShape(shape: ParaShape, availWidth: Double, fromChar: Int): List<BrokenLine> {
        val text = shape.text
        val len = text.length
        val start0 = fromChar.coerceIn(0, len)
        if (start0 >= len) {
            return listOf(lineOf(shape, start0, len, 0.0, 0))
        }
        val out = mutableListOf<BrokenLine>()
        var i = start0
        while (i < len) {
            var w = 0.0
            var wAtLastContent = 0.0
            var spacesBeforeLastContent = 0
            var spacesSeen = 0
            var lastBreak = -1
            var lastBreakWidth = 0.0
            var lastBreakSpaces = 0
            var j = i
            var emitted = false
            while (j < len) {
                val c = text[j]
                val a = shape.adv[j]
                if (c == ' ') {
                    // Spaces hang: they widen the running total but never overflow a line.
                    spacesSeen++
                    w += a
                    j++
                    continue
                }
                // A non-space after spaces marks a break opportunity at its start.
                if (j > i && text[j - 1] == ' ') {
                    lastBreak = j
                    lastBreakWidth = wAtLastContent
                    lastBreakSpaces = spacesBeforeLastContent
                }
                if (w + a > availWidth && j > i) {
                    if (lastBreak > i) {
                        out.add(lineOf(shape, i, lastBreak, lastBreakWidth, lastBreakSpaces))
                        i = lastBreak
                    } else {
                        out.add(lineOf(shape, i, j, w, spacesSeen, hardBroken = true))
                        i = j
                    }
                    emitted = true
                    break
                }
                w += a
                wAtLastContent = w
                spacesBeforeLastContent = spacesSeen
                j++
            }
            if (!emitted) {
                out.add(lineOf(shape, i, len, wAtLastContent, spacesBeforeLastContent))
                i = len
            }
        }
        return out
    }

    private fun lineOf(
        shape: ParaShape,
        start: Int,
        end: Int,
        width: Double,
        spaceCount: Int,
        hardBroken: Boolean = false,
    ): BrokenLine {
        var ascent = 0.0
        var descent = 0.0
        if (start >= end) {
            ascent = shape.emptyMetrics.ascent
            descent = shape.emptyMetrics.descent
        } else {
            var runStart = 0
            for (r in shape.runEnds.indices) {
                val runEnd = shape.runEnds[r]
                if (runEnd > start && runStart < end) {
                    val m = shape.runMetrics[r]
                    if (m.ascent > ascent) ascent = m.ascent
                    if (m.descent > descent) descent = m.descent
                }
                runStart = runEnd
            }
            if (ascent == 0.0 && descent == 0.0) {
                ascent = shape.emptyMetrics.ascent
                descent = shape.emptyMetrics.descent
            }
        }
        return BrokenLine(start, end, width, ascent, descent, spaceCount, hardBroken)
    }

    /** Drop cached measurements for paragraphs no longer in [flow] (call after big splices). */
    fun pruneCaches(flow: TextFlow) {
        val live = HashSet<Paragraph>(flow.paragraphs)
        shapes.keys.retainAll(live)
        breaks.keys.retainAll(live)
    }

    companion object {
        /** Indent step per level, content px at 150 dpi (~8 mm). */
        const val INDENT_STEP_PX = 48.0

        /** Gutter reserved left of list paragraphs for bullet/number/checkbox markers. */
        const val MARKER_GUTTER_PX = 40.0

        /** Layout never wraps narrower than this, however extreme the margins. */
        const val MIN_LINE_WIDTH = 20.0
    }
}
