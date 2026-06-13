package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import kotlin.math.ceil

/**
 * Paints a page-background ruling (lines / dots / grid) in **page-local content space** (origin at
 * the page's top-left, units = content px), behind the ink. Driven from the cached background layer
 * (`CanvasState.paintPageBackground`), so it never re-rasterizes on an ink edit and composites under
 * strokes. [region] is the page-local rect actually being painted — the whole page for the page
 * cache / thumbnails / presentation, or just the visible sub-rect for the deep-zoom sharp viewport —
 * used to skip primitives outside it. Thickness is fixed ([PageStyle.LINE_THICKNESS] /
 * [PageStyle.DOT_RADIUS]); [spacing] is the pattern period. The pen is content-space ([Pen.cosmetic]
 * = false) so the ruling scales with zoom like the page it belongs to.
 */
fun paintPagePattern(
    r: Renderer,
    pattern: PagePattern,
    color: Rgba,
    spacing: Double,
    pageW: Double,
    pageH: Double,
    region: Rect,
) {
    if (pattern == PagePattern.NONE || spacing < 1.0 || color.a == 0) return
    // Honour the pattern opacity uniformly: draw the ruling *opaquely inside an alpha layer* rather
    // than with a per-primitive translucent colour. This survives PDF export (whose vector renderer
    // ignores per-fill alpha but does honour a layer's constant alpha) and keeps grid-line
    // intersections from doubling up in darkness.
    val translucent = color.a < 255
    if (translucent) r.saveLayerAlpha(region, color.a / 255.0)
    val ink = if (translucent) color.copy(a = 255) else color
    val pen = Pen(ink, width = PageStyle.LINE_THICKNESS, cosmetic = false)
    when (pattern) {
        PagePattern.LINES -> hLines(r, pen, spacing, pageW, pageH, region)
        PagePattern.GRID -> {
            hLines(r, pen, spacing, pageW, pageH, region)
            vLines(r, pen, spacing, pageW, pageH, region)
        }
        PagePattern.DOTS -> {
            var y = first(region.top, spacing)
            while (y <= region.bottom && y < pageH) {
                var x = first(region.left, spacing)
                while (x <= region.right && x < pageW) {
                    r.fillCircle(Pt(x, y), PageStyle.DOT_RADIUS, ink)
                    x += spacing
                }
                y += spacing
            }
        }
        PagePattern.NONE -> {}
    }
    if (translucent) r.restore()
}

/** The first ruling coordinate at or after [start], skipping the page-edge line at 0. */
private fun first(start: Double, spacing: Double): Double {
    val v = ceil(start / spacing) * spacing
    return if (v < spacing) spacing else v
}

private fun hLines(r: Renderer, pen: Pen, spacing: Double, pageW: Double, pageH: Double, region: Rect) {
    var y = first(region.top, spacing)
    while (y <= region.bottom && y < pageH) {
        r.strokePolyline(listOf(Pt(0.0, y), Pt(pageW, y)), pen)
        y += spacing
    }
}

private fun vLines(r: Renderer, pen: Pen, spacing: Double, pageW: Double, pageH: Double, region: Rect) {
    var x = first(region.left, spacing)
    while (x <= region.right && x < pageW) {
        r.strokePolyline(listOf(Pt(x, 0.0), Pt(x, pageH)), pen)
        x += spacing
    }
}
