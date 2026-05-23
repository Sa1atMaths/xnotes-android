package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TextItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which resize handle is being dragged (spec 06 §8). */
enum class HandleId { TL, TR, BL, BR, L, R, START, END }

data class ResizeHandle(val id: HandleId, val content: Pt)

/** Pure resize-handle geometry and per-type resize updates (spec 06 §8). */
object ResizeMath {
    const val MIN_SIZE = 24.0

    /** Handle positions in content space for [item] on a page at [pageTopLeft]. */
    fun handles(item: CanvasItem, pageTopLeft: Pt): List<ResizeHandle> = when (item) {
        is ImageItem -> rectCorners(item.rect, pageTopLeft)
        is TextItem -> {
            val b = item.bounds()
            listOf(
                ResizeHandle(HandleId.L, Pt(b.left + pageTopLeft.x, b.centerY + pageTopLeft.y)),
                ResizeHandle(HandleId.R, Pt(b.right + pageTopLeft.x, b.centerY + pageTopLeft.y)),
            )
        }
        is ShapeItem ->
            if (item.shape.isClosed) {
                rectCorners(item.box, pageTopLeft)
            } else {
                listOf(
                    ResizeHandle(HandleId.START, Pt(item.start.x + pageTopLeft.x, item.start.y + pageTopLeft.y)),
                    ResizeHandle(HandleId.END, Pt(item.end.x + pageTopLeft.x, item.end.y + pageTopLeft.y)),
                )
            }
        else -> emptyList()
    }

    private fun rectCorners(rect: Rect, ptl: Pt) = listOf(
        ResizeHandle(HandleId.TL, Pt(rect.left + ptl.x, rect.top + ptl.y)),
        ResizeHandle(HandleId.TR, Pt(rect.right + ptl.x, rect.top + ptl.y)),
        ResizeHandle(HandleId.BL, Pt(rect.left + ptl.x, rect.bottom + ptl.y)),
        ResizeHandle(HandleId.BR, Pt(rect.right + ptl.x, rect.bottom + ptl.y)),
    )

    fun hitHandle(handles: List<ResizeHandle>, content: Pt, tolerance: Double): HandleId? =
        handles.firstOrNull { it.content.distanceTo(content) <= tolerance }?.id

    private fun cornerAnchor(box: Rect, handle: HandleId): Pt = when (handle) {
        HandleId.TL -> Pt(box.right, box.bottom)
        HandleId.TR -> Pt(box.left, box.bottom)
        HandleId.BL -> Pt(box.right, box.top)
        else -> Pt(box.left, box.top) // BR
    }

    /** Aspect-locked image resize (page-local pointer). */
    fun resizeImage(old: Rect, handle: HandleId, pointer: Pt): Rect {
        val anchor = cornerAnchor(old, handle)
        val rawW = abs(pointer.x - anchor.x)
        val rawH = abs(pointer.y - anchor.y)
        var scale = max(rawW / old.w, rawH / old.h)
        scale = max(scale, max(MIN_SIZE / old.w, MIN_SIZE / old.h))
        val newW = old.w * scale
        val newH = old.h * scale
        val left = if (handle == HandleId.TL || handle == HandleId.BL) anchor.x - newW else anchor.x
        val top = if (handle == HandleId.TL || handle == HandleId.TR) anchor.y - newH else anchor.y
        return Rect(left, top, newW, newH)
    }

    /** Free (per-axis) closed-shape resize; returns new (start, end). */
    fun resizeClosedShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> {
        val anchor = cornerAnchor(Rect.fromPoints(start, end), handle)
        var px = pointer.x
        var py = pointer.y
        if (abs(px - anchor.x) < MIN_SIZE) px = anchor.x + if (px >= anchor.x) MIN_SIZE else -MIN_SIZE
        if (abs(py - anchor.y) < MIN_SIZE) py = anchor.y + if (py >= anchor.y) MIN_SIZE else -MIN_SIZE
        return anchor to Pt(px, py)
    }

    /** Open-shape resize: drag the grabbed endpoint; the other stays fixed. */
    fun resizeOpenShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> =
        if (handle == HandleId.START) pointer to end else start to pointer

    /** Text box: width only (height follows the text); returns new (pos, width). */
    fun resizeText(pos: Pt, width: Double, handle: HandleId, pointerX: Double): Pair<Pt, Double> =
        if (handle == HandleId.R) {
            pos to max(MIN_SIZE, pointerX - pos.x)
        } else {
            val right = pos.x + width
            val newX = min(pointerX, right - MIN_SIZE)
            Pt(newX, pos.y) to (right - newX)
        }
}
