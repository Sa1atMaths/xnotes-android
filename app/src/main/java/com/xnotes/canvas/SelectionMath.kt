package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page

/** A selected item plus the index of the page it lives on. */
data class Selected(val pageIndex: Int, val item: CanvasItem)

/** Pure selection-membership tests (spec 06 §6–7). */
object SelectionMath {

    /**
     * Band selection: every item whose content-space bounds intersect [band]. [toContentRect]
     * maps an item's page-space bounds on page i into content space (a plain page-rect
     * translation, plus the display rotation when the view is rotated).
     */
    fun bandMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        band: Rect,
        toContentRect: (Int, Rect) -> Rect = { i, r -> r.translate(pageRects[i].left, pageRects[i].top) },
    ): List<Selected> {
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            if (pageRects.getOrNull(i) == null) continue
            for (item in pages[i].items) {
                if (toContentRect(i, item.bounds()).intersects(band)) out.add(Selected(i, item))
            }
        }
        return out
    }

    /**
     * Lasso selection: every item whose content-space centroid lies inside [polygon] (even-odd).
     * [toContent] maps a page-space point on page i into content space, like [bandMembers].
     */
    fun lassoMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        polygon: List<Pt>,
        toContent: (Int, Pt) -> Pt = { i, p -> Pt(p.x + pageRects[i].left, p.y + pageRects[i].top) },
    ): List<Selected> {
        if (polygon.size < 3) return emptyList()
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            if (pageRects.getOrNull(i) == null) continue
            for (item in pages[i].items) {
                if (Geometry.pointInPolygon(polygon, toContent(i, item.centroid()))) {
                    out.add(Selected(i, item))
                }
            }
        }
        return out
    }
}
