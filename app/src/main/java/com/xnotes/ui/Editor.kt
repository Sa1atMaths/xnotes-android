package com.xnotes.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.InteractionController
import com.xnotes.core.history.AddPage
import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.ui.theme.Palette
import kotlin.math.roundToInt

/**
 * The app-side glue between the imperative canvas (CanvasView + CanvasState +
 * InteractionController + History) and the Compose chrome. Exposes Compose-
 * observable state and the actions the toolbar/menus invoke.
 */
@Stable
class Editor(context: Context, initialPalette: Palette) {

    val state = CanvasState(Document.blank(), AndroidSurfaceFactory(), initialPalette)
    val history = History()
    val view = CanvasView(context).also { it.state = state }

    var tool by mutableStateOf(Tool.DEFAULT)
        private set
    var palette by mutableStateOf(initialPalette)
        private set
    var zoomPercent by mutableStateOf(100)
        private set
    var pageIndex by mutableStateOf(0)
        private set
    var pageCount by mutableStateOf(state.document.pages.size)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var activeColorIndex by mutableStateOf(0)
        private set
    var toolbarColors by mutableStateOf(InkPalette.toolbarDefaults)
        private set
    var renderScale by mutableStateOf(1.0)
        private set
    var sidebarVisible by mutableStateOf(false)
    var hasSelection by mutableStateOf(false)
        private set
    var shapeConfig by mutableStateOf(ShapeConfig())
        private set
    var message by mutableStateOf<String?>(null)

    val controller = InteractionController(
        state,
        history,
        requestRender = { view.requestRender() },
        onContentChanged = { refreshContent() },
        onViewChanged = { refreshView() },
        onSelectionChanged = { selected -> hasSelection = selected },
        onToolChanged = { t -> tool = t },
    )

    init {
        view.input = { controller.onTouch(it) }
        view.hover = { controller.onHover(it) }
        view.drawOverlay = { renderer, _ -> controller.drawOverlay(renderer) }
        controller.inkColor = toolbarColors[activeColorIndex]
    }

    private fun refreshView() {
        zoomPercent = (state.zoom * 100).roundToInt()
        pageIndex = state.currentPageIndex()
    }

    private fun refreshContent() {
        canUndo = history.canUndo
        canRedo = history.canRedo
        pageCount = state.document.pages.size
        refreshView()
    }

    // --- tools & colour ---

    fun selectTool(t: Tool) {
        controller.setTool(t)
        tool = t
    }

    fun pickColor(index: Int) {
        activeColorIndex = index
        controller.inkColor = toolbarColors[index.coerceIn(0, toolbarColors.lastIndex)]
    }

    fun setSwatchColor(index: Int, color: Rgba) {
        toolbarColors = toolbarColors.toMutableList().also { it[index] = color }
        pickColor(index)
    }

    fun setShapeKind(kind: com.xnotes.core.tools.ShapeKind) {
        shapeConfig = shapeConfig.copy(shape = kind)
        controller.shapeConfig = shapeConfig
    }

    // --- history ---

    fun undo() {
        history.undo()
        afterHistory()
    }

    fun redo() {
        history.redo()
        afterHistory()
    }

    private fun afterHistory() {
        controller.clearSelection()
        state.relayout()
        state.invalidateAllCaches()
        state.document.dirty = true
        state.clampScroll()
        refreshContent()
        view.requestRender()
    }

    // --- view ---

    private fun afterView() {
        refreshView()
        view.requestRender()
    }

    fun zoomIn() { state.zoomByStep(true); afterView() }
    fun zoomOut() { state.zoomByStep(false); afterView() }
    fun fitWidth() { state.fitWidth(); afterView() }
    fun fitHeight() { state.fitHeight(); afterView() }
    fun fitPage() { state.fitPage(); afterView() }
    fun prevPage() { state.goToPage(state.currentPageIndex() - 1); afterView() }
    fun nextPage() { state.goToPage(state.currentPageIndex() + 1); afterView() }
    fun goToPage(index: Int) { state.goToPage(index); afterView() }

    fun applyRenderScale(scale: Double) {
        renderScale = scale
        state.renderScale = scale
        state.invalidateAllCaches()
        view.requestRender()
    }

    // --- pages ---

    fun addPage() {
        val ref = state.document.pages.getOrNull(state.currentPageIndex())
        val (w, h) = if (ref != null) {
            ref.width to ref.height
        } else {
            PageSize.A4.pixels(Orientation.PORTRAIT, state.document.dpi)
        }
        val page = Page(w, h)
        val index = state.document.pages.size
        state.document.pages.add(index, page)
        history.push(AddPage(state.document, page, index))
        state.document.dirty = true
        state.relayout()
        refreshContent()
        view.requestRender()
    }

    fun deleteCurrentPage() {
        if (state.document.pages.size <= 1) {
            message = "A note must keep at least one page."
            return
        }
        val index = state.currentPageIndex()
        val page = state.document.pages[index]
        state.document.pages.removeAt(index)
        history.push(DeletePage(state.document, page, index))
        state.document.dirty = true
        state.invalidatePage(page)
        state.relayout()
        refreshContent()
        view.requestRender()
    }

    // --- selection edits ---

    fun deleteSelection() = controller.deleteSelection()
    fun selectAll() = controller.selectAll()
    fun bringToFront() = controller.bringToFront()
    fun escape() = controller.escape()

    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
    }

    fun newNote() {
        state.document = Document.blank()
        history.clear()
        controller.clearSelection()
        state.invalidateAllCaches()
        state.didInitialFit = false
        state.relayout()
        if (state.viewportW > 0) {
            state.fitWidth()
            state.didInitialFit = true
        }
        refreshContent()
        view.requestRender()
    }
}
