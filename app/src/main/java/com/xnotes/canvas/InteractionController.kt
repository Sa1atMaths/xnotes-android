package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.History
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.ReorderItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import kotlin.math.abs

/** The pointer state machine modes (spec 06 §1). */
enum class PointerMode { IDLE, DRAW, ERASE, BAND, LASSO_DRAW, SHAPE, MOVE, RESIZE, PAN, PINCH }

/**
 * Drives editing from pointer input (spec 06): drawing, the object eraser,
 * rubber-band and lasso selection, moving a selection, plus pan/zoom. Resize,
 * shapes, long-press and text are layered on in later commits.
 */
class InteractionController(
    private val state: CanvasState,
    val history: History,
    private val requestRender: () -> Unit,
    private val onContentChanged: () -> Unit = {},
    private val onViewChanged: () -> Unit = {},
    private val onSelectionChanged: (Boolean) -> Unit = {},
) {
    val document: Document get() = state.document

    var tool: Tool = Tool.DEFAULT
        private set
    var inkColor: Rgba = InkPalette.DEFAULT

    private val toolConfigs: MutableMap<Tool, ToolConfig> =
        Tool.entries.associateWith { ToolDefaults.configFor(it) }.toMutableMap()

    private var mode = PointerMode.IDLE

    // DRAW
    private var liveStroke: Stroke? = null
    private var strokePageIndex: Int? = null
    private var drawingPointerId = -1
    private var drawingIsStylus = false

    // PAN
    private var lastPan = Pt.ZERO

    // PINCH
    private var pinchInitDist = 1.0
    private var pinchInitZoom = 1.0
    private var pinchAnchorContent = Pt.ZERO

    // SELECTION
    private val selection = mutableListOf<Selected>()
    private var lassoPolygon: List<Pt>? = null
    private val lassoPoints = mutableListOf<Pt>()
    private var bandRect: Rect? = null
    private var moveOrigin = Pt.ZERO
    private var moveOffset = Pt.ZERO

    // ERASE
    private val eraseRemovals = mutableListOf<Pair<Page, CanvasItem>>()
    private var eraserCursor: Pt? = null // viewport pixels

    init {
        state.isLiftedItem = { item -> selection.any { it.item === item } }
    }

    val hasSelection: Boolean get() = selection.isNotEmpty()

    fun configFor(t: Tool): ToolConfig = toolConfigs[t] ?: ToolDefaults.configFor(t)

    fun setTool(t: Tool) {
        if (t == tool) {
            return
        }
        abortGesture()
        clearSelection()
        eraserCursor = null
        tool = t
        requestRender()
    }

    // --- touch entry point ---

    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(e)
            MotionEvent.ACTION_MOVE -> handleMove(e)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(e)
            MotionEvent.ACTION_UP -> handleUp(e)
            MotionEvent.ACTION_CANCEL -> {
                abortGesture()
                requestRender()
            }
        }
        return true
    }

    fun onHover(e: MotionEvent): Boolean {
        val isEraserPointer = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (tool != Tool.ERASER && !isEraserPointer) return false
        eraserCursor = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            null
        } else {
            Pt(e.x.toDouble(), e.y.toDouble())
        }
        requestRender()
        return true
    }

    private fun handleDown(e: MotionEvent) {
        val toolType = e.getToolType(0)
        val vx = e.getX(0).toDouble()
        val vy = e.getY(0).toDouble()
        val content = state.viewportToContent(Pt(vx, vy))
        drawingPointerId = e.getPointerId(0)
        drawingIsStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS

        // Stylus eraser end erases over any armed tool (spec 06 §2.1).
        if (toolType == MotionEvent.TOOL_TYPE_ERASER) {
            clearSelection()
            beginErase(vx, vy)
            return
        }
        when {
            tool == Tool.PAN -> beginPan(vx, vy)
            tool.isStroke -> beginDraw(content, resolvePressure(e, 0, toolType))
            tool == Tool.ERASER -> beginErase(vx, vy)
            tool == Tool.SELECT -> beginSelect(content)
            tool == Tool.LASSO -> beginLasso(content)
            else -> Unit // shape / text in later commits
        }
    }

    private fun handlePointerDown(e: MotionEvent) {
        if (mode == PointerMode.DRAW && drawingIsStylus) return
        if (mode == PointerMode.ERASE) return
        if (e.pointerCount >= 2) beginPinch(e)
    }

    private fun handleMove(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val content = state.viewportToContent(Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()))
        when (mode) {
            PointerMode.DRAW -> extendDraw(e)
            PointerMode.PAN -> extendPan(e.getX(idx).toDouble(), e.getY(idx).toDouble())
            PointerMode.PINCH -> updatePinch(e)
            PointerMode.ERASE -> eraseAt(e.getX(idx).toDouble(), e.getY(idx).toDouble())
            PointerMode.BAND -> extendBand(content)
            PointerMode.LASSO_DRAW -> extendLasso(content)
            PointerMode.MOVE -> extendMove(content)
            else -> Unit
        }
    }

    private fun handlePointerUp(e: MotionEvent) {
        if (mode == PointerMode.PINCH && e.pointerCount <= 2) endPinch()
    }

    private fun handleUp(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        val content = state.viewportToContent(Pt(e.getX(idx).toDouble(), e.getY(idx).toDouble()))
        when (mode) {
            PointerMode.DRAW -> endDraw(e)
            PointerMode.PAN -> mode = PointerMode.IDLE
            PointerMode.PINCH -> endPinch()
            PointerMode.ERASE -> endErase()
            PointerMode.BAND -> endBand()
            PointerMode.LASSO_DRAW -> endLasso()
            PointerMode.MOVE -> endMove(content)
            else -> Unit
        }
    }

    // --- DRAW ---

    private fun beginDraw(content: Pt, pressure: Double) {
        val pageIndex = state.pageIndexAtContent(content) ?: return
        val pr = state.pageRects[pageIndex]
        val stroke = Stroke(tool, configFor(tool).copy(rgba = inkColor))
        stroke.addSample(Sample(content.x - pr.left, content.y - pr.top, pressure))
        liveStroke = stroke
        strokePageIndex = pageIndex
        mode = PointerMode.DRAW
        requestRender()
    }

    private fun extendDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId)
        if (idx < 0) return
        for (h in 0 until e.historySize) {
            addStrokePoint(
                e.getHistoricalX(idx, h).toDouble(),
                e.getHistoricalY(idx, h).toDouble(),
                if (drawingIsStylus) e.getHistoricalPressure(idx, h).toDouble() else 1.0,
                force = false,
            )
        }
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, force = false,
        )
        requestRender()
    }

    private fun addStrokePoint(vx: Double, vy: Double, pressure: Double, force: Boolean) {
        val stroke = liveStroke ?: return
        val pi = strokePageIndex ?: return
        val pr = state.pageRects.getOrNull(pi) ?: return
        val content = state.viewportToContent(Pt(vx, vy))
        val local = Pt(content.x - pr.left, content.y - pr.top)
        val last = stroke.samples.lastOrNull()
        if (force || last == null || Pt(last.x, last.y).manhattanTo(local) >= MIN_SAMPLE_DIST) {
            stroke.addSample(Sample(local.x, local.y, pressure.coerceIn(0.0, 1.0)))
        }
    }

    private fun endDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(drawingPointerId).coerceAtLeast(0)
        addStrokePoint(
            e.getX(idx).toDouble(), e.getY(idx).toDouble(),
            if (drawingIsStylus) e.getPressure(idx).toDouble() else 1.0, force = true,
        )
        val stroke = liveStroke
        val pi = strokePageIndex
        if (stroke != null && pi != null && !stroke.isEmpty) {
            val page = state.document.pages[pi]
            page.items.add(stroke)
            state.appendToCache(page, stroke)
            history.push(AddItem(page, stroke))
            state.document.dirty = true
            onContentChanged()
        }
        liveStroke = null
        strokePageIndex = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- ERASE ---

    private fun eraserRadius(): Double = configFor(Tool.ERASER).baseWidth

    private fun beginErase(vx: Double, vy: Double) {
        eraseRemovals.clear()
        mode = PointerMode.ERASE
        eraseAt(vx, vy)
    }

    private fun eraseAt(vx: Double, vy: Double) {
        eraserCursor = Pt(vx, vy)
        val content = state.viewportToContent(Pt(vx, vy))
        val radius = eraserRadius()
        var changed = false
        for (pi in state.document.pages.indices) {
            val pr = state.pageRects.getOrNull(pi) ?: continue
            val page = state.document.pages[pi]
            val cx = content.x - pr.left
            val cy = content.y - pr.top
            val toRemove = page.items.filter {
                it !is ImageItem && it !is ShapeItem && it.intersectsCircle(cx, cy, radius)
            }
            if (toRemove.isNotEmpty()) {
                for (item in toRemove) {
                    page.items.remove(item)
                    eraseRemovals.add(page to item)
                }
                state.invalidatePage(page)
                changed = true
            }
        }
        if (changed) onContentChanged()
        requestRender()
    }

    private fun endErase() {
        if (eraseRemovals.isNotEmpty()) {
            history.push(EraseItems(eraseRemovals.toList()))
            state.document.dirty = true
            onContentChanged()
        }
        eraseRemovals.clear()
        eraserCursor = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- SELECT / BAND ---

    private fun beginSelect(content: Pt) {
        // (resize-handle hit-test is added with the resize commit)
        val pageIndex = state.pageIndexAtContent(content)
        if (pageIndex != null) {
            val pr = state.pageRects[pageIndex]
            val local = Pt(content.x - pr.left, content.y - pr.top)
            val hit = state.document.pages[pageIndex].items.lastOrNull { it.contains(local) }
            if (hit != null) {
                if (selection.none { it.item === hit }) setSelection(listOf(Selected(pageIndex, hit)))
                beginMove(content)
                return
            }
        }
        if (selection.isNotEmpty() && selectionBoundsContent()?.contains(content) == true) {
            beginMove(content)
            return
        }
        clearSelection()
        mode = PointerMode.BAND
        moveOrigin = content
        bandRect = Rect.fromPoints(content, content)
    }

    private fun extendBand(content: Pt) {
        bandRect = Rect.fromPoints(moveOrigin, content)
        requestRender()
    }

    private fun endBand() {
        bandRect?.let { setSelection(SelectionMath.bandMembers(state.document.pages, state.pageRects, it)) }
        bandRect = null
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- LASSO ---

    private fun beginLasso(content: Pt) {
        val poly = lassoPolygon
        if (poly != null && selection.isNotEmpty() && com.xnotes.core.geometry.Geometry.pointInPolygon(poly, content)) {
            beginMove(content)
            return
        }
        clearSelection()
        lassoPoints.clear()
        lassoPoints.add(content)
        mode = PointerMode.LASSO_DRAW
    }

    private fun extendLasso(content: Pt) {
        lassoPoints.add(content)
        requestRender()
    }

    private fun endLasso() {
        if (lassoPoints.size >= 3) {
            val members = SelectionMath.lassoMembers(state.document.pages, state.pageRects, lassoPoints)
            if (members.isEmpty()) {
                clearSelection()
            } else {
                setSelection(members)
                lassoPolygon = lassoPoints.toList()
            }
        } else {
            clearSelection()
        }
        lassoPoints.clear()
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- MOVE ---

    private fun beginMove(content: Pt) {
        mode = PointerMode.MOVE
        moveOrigin = content
        moveOffset = Pt.ZERO
    }

    private fun extendMove(content: Pt) {
        moveOffset = content - moveOrigin
        requestRender()
    }

    private fun endMove(content: Pt) {
        moveOffset = content - moveOrigin
        if (abs(moveOffset.x) > MOVE_EPS || abs(moveOffset.y) > MOVE_EPS) {
            val items = selection.map { it.item }
            for (item in items) item.translate(moveOffset.x, moveOffset.y)
            lassoPolygon = lassoPolygon?.map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }
            history.push(MoveItems(items, moveOffset.x, moveOffset.y))
            state.document.dirty = true
            invalidateSelectionPages()
            onContentChanged()
        }
        moveOffset = Pt.ZERO
        mode = PointerMode.IDLE
        requestRender()
    }

    // --- selection management ---

    private fun setSelection(items: List<Selected>) {
        val affected = HashSet<Int>()
        selection.forEach { affected.add(it.pageIndex) }
        selection.clear()
        selection.addAll(items)
        selection.forEach { affected.add(it.pageIndex) }
        lassoPolygon = null
        affected.forEach { idx -> state.document.pages.getOrNull(idx)?.let(state::invalidatePage) }
        onSelectionChanged(selection.isNotEmpty())
        requestRender()
    }

    fun clearSelection() {
        if (selection.isEmpty() && lassoPolygon == null) return
        val affected = selection.map { it.pageIndex }.toSet()
        selection.clear()
        lassoPolygon = null
        affected.forEach { state.document.pages.getOrNull(it)?.let(state::invalidatePage) }
        onSelectionChanged(false)
        requestRender()
    }

    private fun invalidateSelectionPages() {
        selection.map { it.pageIndex }.toSet().forEach {
            state.document.pages.getOrNull(it)?.let(state::invalidatePage)
        }
    }

    private fun selectionBoundsContent(): Rect? {
        if (selection.isEmpty()) return null
        var acc: Rect? = null
        for (sel in selection) {
            val pr = state.pageRects.getOrNull(sel.pageIndex) ?: continue
            val b = sel.item.bounds().translate(pr.left, pr.top)
            acc = acc?.union(b) ?: b
        }
        return acc
    }

    // --- public edit operations (toolbar / context menu) ---

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removals = selection.map { state.document.pages[it.pageIndex] to it.item }
        for ((page, item) in removals) page.items.remove(item)
        history.push(EraseItems(removals))
        state.document.dirty = true
        clearSelection()
        state.invalidateAllCaches()
        onContentChanged()
    }

    fun selectAll() {
        val all = ArrayList<Selected>()
        state.document.pages.forEachIndexed { i, page -> page.items.forEach { all.add(Selected(i, it)) } }
        setSelection(all)
    }

    fun bringToFront() {
        if (selection.isEmpty()) return
        val byPage = selection.groupBy { it.pageIndex }
        for ((pageIndex, sels) in byPage) {
            val page = state.document.pages.getOrNull(pageIndex) ?: continue
            val selectedSet = sels.map { it.item }.toSet()
            val old = page.items.toList()
            val kept = old.filter { it !in selectedSet }
            val moved = old.filter { it in selectedSet }
            val new = kept + moved
            if (new != old) {
                history.push(ReorderItems(page, old, new))
                page.items.clear()
                page.items.addAll(new)
                state.invalidatePage(page)
            }
        }
        state.document.dirty = true
        onContentChanged()
    }

    fun escape() {
        clearSelection()
        requestRender()
    }

    // --- PAN ---

    private fun beginPan(vx: Double, vy: Double) {
        mode = PointerMode.PAN
        lastPan = Pt(vx, vy)
    }

    private fun extendPan(vx: Double, vy: Double) {
        state.scrollBy(-(vx - lastPan.x), -(vy - lastPan.y))
        lastPan = Pt(vx, vy)
        onViewChanged()
        requestRender()
    }

    // --- PINCH ---

    private fun beginPinch(e: MotionEvent) {
        liveStroke = null
        strokePageIndex = null
        bandRect = null
        lassoPoints.clear()
        mode = PointerMode.PINCH
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val mid = (a + b) * 0.5
        pinchInitDist = a.distanceTo(b).coerceAtLeast(1.0)
        pinchInitZoom = state.zoom
        pinchAnchorContent = state.viewportToContent(mid)
        state.zoomingInProgress = true
    }

    private fun updatePinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = Pt(e.getX(0).toDouble(), e.getY(0).toDouble())
        val b = Pt(e.getX(1).toDouble(), e.getY(1).toDouble())
        val dist = a.distanceTo(b)
        if (dist < 1e-3) return
        val mid = (a + b) * 0.5
        val z = (pinchInitZoom * (dist / pinchInitDist)).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
        state.zoom = z
        state.scrollX = pinchAnchorContent.x * z - mid.x
        state.scrollY = pinchAnchorContent.y * z - mid.y
        state.clampScroll()
        requestRender()
    }

    private fun endPinch() {
        mode = PointerMode.IDLE
        state.zoomingInProgress = false
        state.invalidateAllCaches()
        onViewChanged()
        requestRender()
    }

    private fun abortGesture() {
        liveStroke = null
        strokePageIndex = null
        bandRect = null
        lassoPoints.clear()
        if (mode == PointerMode.PINCH) {
            state.zoomingInProgress = false
            state.invalidateAllCaches()
        }
        mode = PointerMode.IDLE
    }

    private fun resolvePressure(e: MotionEvent, pointerIndex: Int, toolType: Int): Double =
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) e.getPressure(pointerIndex).toDouble().coerceIn(0.0, 1.0) else 1.0

    // --- overlay ---

    fun drawOverlay(r: Renderer) {
        val origin = state.origin()
        r.withSave {
            r.translate(origin.x, origin.y)
            r.scale(state.zoom, state.zoom)

            // Lifted (selected) items, drawn live at the move offset.
            for (sel in selection) {
                val pr = state.pageRects.getOrNull(sel.pageIndex) ?: continue
                r.withSave {
                    r.translate(pr.left + moveOffset.x, pr.top + moveOffset.y)
                    sel.item.paint(r)
                }
            }

            // Live in-progress stroke, clipped to its page.
            liveStroke?.let { stroke ->
                val pr = state.pageRects.getOrNull(strokePageIndex ?: -1)
                if (pr != null) {
                    r.withSave {
                        r.clipRect(pr)
                        r.translate(pr.left, pr.top)
                        stroke.paint(r)
                    }
                }
            }

            // Selection chrome.
            val accent = Pen(state.palette.accent, 1.3, cosmetic = true, dashed = true)
            when {
                mode == PointerMode.BAND -> bandRect?.let { r.strokeRect(it, accent) }
                mode == PointerMode.LASSO_DRAW && lassoPoints.size >= 2 ->
                    r.strokePolyline(lassoPoints, Pen(state.palette.accent, 1.3, cosmetic = true))
                lassoPolygon != null && selection.isNotEmpty() ->
                    r.strokePolygon(lassoPolygon!!.map { Pt(it.x + moveOffset.x, it.y + moveOffset.y) }, accent)
                selection.isNotEmpty() ->
                    selectionBoundsContent()?.translate(moveOffset.x, moveOffset.y)?.let { r.strokeRect(it, accent) }
            }
        }

        // Eraser cursor (viewport space, after the transform is restored).
        eraserCursor?.let {
            val radius = eraserRadius() * state.zoom
            r.strokeEllipse(it, radius, radius, Pen(state.palette.textDim, 1.3, cosmetic = true))
        }
    }

    companion object {
        const val MIN_SAMPLE_DIST = 1.0
        const val MOVE_EPS = 0.01
    }
}
