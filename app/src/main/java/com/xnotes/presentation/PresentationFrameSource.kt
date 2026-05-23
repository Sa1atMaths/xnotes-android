package com.xnotes.presentation

import com.xnotes.canvas.CanvasState
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Renderer
import com.xnotes.platform.AndroidRasterSurface
import kotlin.math.ceil
import kotlin.math.max

/**
 * Renders a presentation frame by compositing the on-screen page cache (the same
 * background + committed-ink bitmap the canvas blits, kept current incrementally)
 * with the live in-progress stroke and any lifted items — so a frame costs a
 * downscale-blit, not a full repaint of every stroke (spec 12 §3). Excludes
 * presenter-only chrome (eraser cursor, selection handles, page labels). Page mode
 * frames a whole page; follow mode mirrors the presenter's viewport.
 */
class PresentationFrameSource(
    private val state: CanvasState,
    private val liveStroke: () -> Pair<Int, Stroke>?,
) {
    /** Render the current page whole, fit within [longEdgeCap]. */
    fun renderPage(longEdgeCap: Int): AndroidRasterSurface {
        val index = state.currentPageIndex().coerceIn(0, state.document.pages.lastIndex.coerceAtLeast(0))
        val page = state.document.pages.getOrNull(index)
            ?: return AndroidRasterSurface.create(longEdgeCap, longEdgeCap)
        val scale = longEdgeCap / max(page.width, page.height)
        val w = ceil(page.width * scale).toInt().coerceAtLeast(1)
        val h = ceil(page.height * scale).toInt().coerceAtLeast(1)
        val surface = AndroidRasterSurface.create(w, h)
        surface.fill(state.paperColor(page))
        val r = surface.renderer()
        r.scale(scale, scale)
        paintPageContent(r, page, scale)
        liveStroke()?.let { (pi, stroke) -> if (pi == index) stroke.paint(r) }
        return surface
    }

    /** Mirror the presenter's viewport, fit within [longEdgeCap]. */
    fun renderFollow(longEdgeCap: Int): AndroidRasterSurface {
        val vw = state.viewportW.toDouble()
        val vh = state.viewportH.toDouble()
        if (vw <= 0 || vh <= 0) return renderPage(longEdgeCap)

        val s = longEdgeCap / max(vw, vh)
        val w = ceil(vw * s).toInt().coerceAtLeast(1)
        val h = ceil(vh * s).toInt().coerceAtLeast(1)
        val surface = AndroidRasterSurface.create(w, h)
        surface.fill(state.palette.bg)
        val r = surface.renderer()
        r.scale(s, s)
        val origin = state.origin()
        r.translate(origin.x, origin.y)
        r.scale(state.zoom, state.zoom)

        val visible = state.visibleContentRect()
        val live = liveStroke()
        for (i in state.document.pages.indices) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val page = state.document.pages[i]
            r.withSave {
                r.translate(pr.left, pr.top)
                r.fillRect(Rect(0.0, 0.0, page.width, page.height), state.paperColor(page))
                paintPageContent(r, page, state.zoom * s)
                if (live != null && live.first == i) live.second.paint(r)
            }
        }
        return surface
    }

    /**
     * Paint [page]'s background + committed ink into [r] (already scaled
     * content→frame by [targetScale]). Blits the on-screen page cache when it is at
     * least as detailed as the frame — the common case while presenting at
     * normal/high zoom — so a frame costs a downscale-blit rather than repainting
     * every stroke. Falls back to painting items directly when the presenter is
     * zoomed out far enough that the cache would upscale and blur.
     */
    private fun paintPageContent(r: Renderer, page: Page, targetScale: Double) {
        val cache = state.cacheFor(page)
        if (cache.res >= targetScale) {
            r.drawRaster(cache.surface, Rect(0.0, 0.0, page.width, page.height))
            for (item in page.items) if (state.isLiftedItem(item)) item.paint(r)
        } else {
            state.paintPageBackground?.invoke(page, r, targetScale)
            for (item in page.items) item.paint(r)
        }
    }
}
