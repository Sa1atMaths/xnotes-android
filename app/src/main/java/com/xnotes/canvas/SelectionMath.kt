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

    /** Band selection: every item whose page-translated bounds intersect [band] (content space). */
    fun bandMembers(pages: List<Page>, pageRects: List<Rect>, band: Rect): List<Selected> {
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            for (item in pages[i].items) {
                val b = item.bounds().translate(pr.left, pr.top)
                if (b.intersects(band)) out.add(Selected(i, item))
            }
        }
        return out
    }

    /** Lasso selection: every item whose page-translated centroid lies inside [polygon] (even-odd). */
    fun lassoMembers(pages: List<Page>, pageRects: List<Rect>, polygon: List<Pt>): List<Selected> {
        if (polygon.size < 3) return emptyList()
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            for (item in pages[i].items) {
                val c = item.centroid()
                if (Geometry.pointInPolygon(polygon, Pt(c.x + pr.left, c.y + pr.top))) {
                    out.add(Selected(i, item))
                }
            }
        }
        return out
    }
}
