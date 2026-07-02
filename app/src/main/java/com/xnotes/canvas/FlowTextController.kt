package com.xnotes.canvas

import android.os.Handler
import android.os.Looper
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddPageAuto
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.FlowEditParagraph
import com.xnotes.core.history.History
import com.xnotes.core.history.ParaSnapshot
import com.xnotes.core.model.Page
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowEditor
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowHit
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.TextFlow
import com.xnotes.core.text.wordRangeAt
import kotlin.math.abs

/**
 * The inline-flow caret: session lifecycle (lift the flow for live drawing, show
 * the IME), tap-to-caret with empty-line fill below the text, drag selection,
 * double-tap word selection, checkbox toggling, typing bursts coalesced into one
 * undo step, and automatic page append when typing overflows the last page.
 * Owned by the Editor; the InteractionController routes TEXT-tool gestures here.
 */
class FlowTextController(
    private val state: CanvasState,
    private val history: History,
    private val flow: () -> TextFlow,
    private val frame: () -> FlowFrame?,
    private val slotHeight: () -> Double,
    /** A mutation landed; republish the layout ([live] = a caret session is open). */
    private val onChanged: (live: Boolean) -> Unit,
    /** A coalesced burst (one undo step) was pushed: refresh chrome/autosave/stream. */
    private val onFlushed: () -> Unit,
    private val onSessionChanged: (Boolean) -> Unit,
    private val onViewChanged: () -> Unit,
    private val requestRender: () -> Unit,
) {
    var active = false
        private set

    var selection: FlowRange = FlowRange.caret(FlowPos.START)
        private set

    /** Style for the next typed run (set by the format bar on a collapsed caret). */
    var pendingStyle: CharStyle? = null

    /** Installed by the input layer to mirror caret/selection moves to the IME. */
    var imeSync: () -> Unit = {}

    /** Installed by the input layer: a committed edit landed (reconcile the IME mirror). */
    var onEdited: () -> Unit = {}

    private val handler = Handler(Looper.getMainLooper())
    private val idleFlush = Runnable { flushBurst() }

    // one coalesced typing burst: a single paragraph edited since the last flush
    private var burstPara: Paragraph? = null
    private var burstBefore: ParaSnapshot? = null
    private val burstExtras = mutableListOf<Command>()

    // press/drag gesture state
    private var pressAnchor: FlowPos? = null
    private var pressHit: FlowHit? = null
    private var pressViewport = Pt(0.0, 0.0)
    private var dragging = false
    private var lastTapAtMs = 0L
    private var lastTapViewport = Pt(0.0, 0.0)

    // --- session ---

    fun startSession(range: FlowRange) {
        if (active) {
            selection = range
            imeSync()
            requestRender()
            return
        }
        active = true
        selection = range
        state.flowLifted = true
        invalidateFlowPages()
        onSessionChanged(true)
        requestRender()
    }

    fun endSession() {
        if (!active) return
        flushBurst()
        active = false
        state.flowLifted = false
        invalidateFlowPages()
        onSessionChanged(false)
        requestRender()
    }

    private fun invalidateFlowPages() {
        val f = frame() ?: return
        val pages = state.document.pages
        f.pagesWithLines().forEach { i -> pages.getOrNull(i)?.let(state::invalidatePage) }
    }

    // --- gestures (routed by the InteractionController while the TEXT tool is armed) ---

    fun pressAt(content: Pt, viewport: Pt) {
        pressViewport = viewport
        dragging = false
        val (pi, local) = pagePointAt(content) ?: return
        val hit = frame()?.hitTest(pi, local) ?: return
        pressHit = hit
        pressAnchor = when (hit) {
            is FlowHit.Caret -> hit.pos
            is FlowHit.Checkbox -> FlowPos(hit.paraIndex, 0)
            FlowHit.BeyondEnd -> flow().endPos()
        }
    }

    fun dragTo(content: Pt, viewport: Pt) {
        val anchor = pressAnchor ?: return
        if (!dragging && viewport.distanceTo(pressViewport) <= DRAG_SLOP) return
        if (!dragging) {
            dragging = true
            if (!active) startSession(FlowRange.caret(anchor))
        }
        selection = FlowRange(anchor, caretPosAt(content) ?: return)
        requestRender()
    }

    fun release(content: Pt, viewport: Pt, timeMs: Long) {
        val hit = pressHit
        pressHit = null
        pressAnchor = null
        if (dragging) {
            dragging = false
            imeSync()
            requestRender()
            return
        }
        when (hit) {
            null -> Unit
            is FlowHit.Checkbox -> {
                flushBurst()
                commitEdit(FlowEditor(flow()).toggleChecked(hit.paraIndex), null)
            }
            is FlowHit.Caret -> {
                val isDouble = timeMs - lastTapAtMs < DOUBLE_TAP_MS &&
                    viewport.distanceTo(lastTapViewport) < DOUBLE_TAP_SLOP
                lastTapAtMs = timeMs
                lastTapViewport = viewport
                if (active) placeCaret(hit.pos) else startSession(FlowRange.caret(hit.pos))
                if (isDouble) {
                    selection = wordRangeAt(flow(), hit.pos)
                    imeSync()
                    requestRender()
                }
            }
            FlowHit.BeyondEnd -> {
                lastTapAtMs = timeMs
                lastTapViewport = viewport
                tapBeyondEnd(content)
            }
        }
    }

    /** A tap below the flow end: fill the gap with empty lines so the caret lands there. */
    private fun tapBeyondEnd(content: Pt) {
        val (pi, local) = pagePointAt(content) ?: return
        val f = frame() ?: return
        var count = f.emptyLinesToReach(pi, local.y, slotHeight())
        if (flow().paragraphs.isEmpty() && count <= 0) count = 1
        if (!active) startSession(FlowRange.caret(flow().endPos()))
        if (count <= 0) {
            placeCaret(flow().endPos())
            return
        }
        flushBurst()
        val (cmd, caret) = FlowEditor(flow()).appendEmptyLines(count)
        commitEdit(cmd, caret)
    }

    fun placeCaret(pos: FlowPos) {
        flushBurst()
        selection = FlowRange.caret(pos)
        imeSync()
        ensureCaretVisible()
        requestRender()
    }

    fun setSelection(range: FlowRange, syncIme: Boolean = true) {
        selection = range
        if (syncIme) imeSync()
        requestRender()
    }

    fun selectAll() {
        if (flow().paragraphs.isEmpty()) return
        selection = FlowRange(FlowPos.START, flow().endPos())
        imeSync()
        requestRender()
    }

    // --- edits ---

    /**
     * Replace [range] with [text] (the IME/typing path). Single-paragraph edits
     * coalesce into the open burst; anything structural flushes first and lands
     * as its own undo step. Returns the caret position after the edit.
     */
    fun applyReplace(range: FlowRange, text: String, style: CharStyle? = null): FlowPos {
        val r = range.normalized()
        val para = flow().paragraphs.getOrNull(r.start.para)
        val effStyle = style ?: pendingStyle?.also { pendingStyle = null }
        if (r.start.para == r.end.para && '\n' !in text && para != null) {
            if (burstPara !== para) {
                flushBurst()
                burstPara = para
                burstBefore = ParaSnapshot.of(para)
            }
            val (_, caret) = FlowEditor(flow()).replaceRange(r, text, effStyle)
            onChanged(active)
            burstExtras += autoAppendPages()
            selection = FlowRange.caret(caret)
            handler.removeCallbacks(idleFlush)
            handler.postDelayed(idleFlush, BURST_IDLE_MS)
            ensureCaretVisible()
            requestRender()
            return caret
        }
        flushBurst()
        val (cmd, caret) = FlowEditor(flow()).replaceRange(r, text, effStyle)
        commitEdit(cmd, caret)
        return caret
    }

    /**
     * Replace [range] from OUTSIDE the IME mirror (menu paste, programmatic edits):
     * always its own undo step through [commitEdit], so the mirror reconciles.
     */
    fun replaceExternal(range: FlowRange, text: String, style: CharStyle? = null): FlowPos {
        flushBurst()
        val (cmd, caret) = FlowEditor(flow()).replaceRange(range.normalized(), text, style)
        commitEdit(cmd, caret)
        return caret
    }

    /** Apply an already-built (and applied) command from the format bar / menus. */
    fun commitEdit(cmd: Command?, caretTo: FlowPos?) {
        if (cmd == null) {
            caretTo?.let { placeCaret(it) }
            return
        }
        onChanged(active)
        val extras = autoAppendPages()
        history.push(if (extras.isEmpty()) cmd else CompositeCommand(listOf(cmd) + extras))
        caretTo?.let { selection = FlowRange.caret(it) }
        onEdited()
        onFlushed()
        ensureCaretVisible()
        requestRender()
    }

    /** Push the open typing burst (plus any auto-added pages) as one undo step. */
    fun flushBurst() {
        handler.removeCallbacks(idleFlush)
        val para = burstPara ?: return
        val before = burstBefore
        burstPara = null
        burstBefore = null
        val extras = burstExtras.toList()
        burstExtras.clear()
        val edit = if (before != null && !before.matches(para)) {
            FlowEditParagraph(para, before, ParaSnapshot.of(para))
        } else {
            null
        }
        val cmds = listOfNotNull(edit) + extras
        when {
            cmds.isEmpty() -> return
            cmds.size == 1 -> history.push(cmds[0])
            else -> history.push(CompositeCommand(cmds))
        }
        onFlushed()
    }

    /** Append pages sized like the last one until the published layout stops overflowing. */
    private fun autoAppendPages(): List<Command> {
        val need = frame()?.extraPagesNeeded ?: 0
        if (need <= 0) return emptyList()
        val doc = state.document
        val out = mutableListOf<Command>()
        repeat(need) {
            val last = doc.pages.lastOrNull() ?: return out
            out += AddPageAuto(doc, Page(last.width, last.height), doc.pages.size).also { it.redo() }
        }
        state.relayout()
        onChanged(active)
        return out
    }

    // --- geometry ---

    fun ensureCaretVisible() {
        if (!active) return
        val f = frame() ?: return
        val (pi, rect) = f.caretRect(selection.end) ?: return
        val pr = state.pageRects.getOrNull(pi) ?: return
        val topV = state.contentToViewport(Pt(0.0, pr.top + rect.top)).y
        val bottomV = state.contentToViewport(Pt(0.0, pr.top + rect.bottom)).y
        val margin = CARET_MARGIN * state.devicePxPerDp
        val top = margin
        val bottom = state.viewportH - margin
        if (bottom <= top) return
        val dy = when {
            bottomV > bottom -> bottomV - bottom
            topV < top -> topV - top
            else -> 0.0
        }
        if (abs(dy) < 1.0) return
        state.scrollBy(0.0, dy)
        onViewChanged()
        requestRender()
    }

    /** The caret's on-screen rect (viewport px), for the IME's cursor anchor. */
    fun caretViewportRect(): Rect? {
        val f = frame() ?: return null
        val (pi, rect) = f.caretRect(selection.end) ?: return null
        val pr = state.pageRects.getOrNull(pi) ?: return null
        val tl = state.contentToViewport(Pt(pr.left + rect.left, pr.top + rect.top))
        return Rect(tl.x, tl.y, rect.w * state.zoom, rect.h * state.zoom)
    }

    /** Selection highlight + caret, drawn in the interaction overlay (content space). */
    fun drawOverlay(r: com.xnotes.core.pal.Renderer) {
        if (!active) return
        val f = frame() ?: return
        val accent = state.palette.accent
        if (!selection.collapsed) {
            for ((pi, rect) in f.selectionRects(selection)) {
                val pr = state.pageRects.getOrNull(pi) ?: continue
                r.fillRect(rect.translate(pr.left, pr.top), accent.withAlpha(70))
            }
        } else {
            val (pi, cr) = f.caretRect(selection.end) ?: return
            val pr = state.pageRects.getOrNull(pi) ?: return
            val w = (FlowFrame.CARET_WIDTH / state.zoom).coerceAtLeast(0.75)
            r.fillRect(Rect(pr.left + cr.left - w / 2.0, pr.top + cr.top, w, cr.h), accent)
        }
    }

    private fun caretPosAt(content: Pt): FlowPos? {
        val (pi, local) = pagePointAt(content) ?: return null
        return when (val hit = frame()?.hitTest(pi, local) ?: return null) {
            is FlowHit.Caret -> hit.pos
            is FlowHit.Checkbox -> FlowPos(hit.paraIndex, 0)
            FlowHit.BeyondEnd -> flow().endPos()
        }
    }

    /** The page under [content], or the nearest one (drags cross the gaps between pages). */
    private fun pagePointAt(content: Pt): Pair<Int, Pt>? {
        var pi = state.pageIndexAtContent(content)
        if (pi == null) {
            var bestD = Double.MAX_VALUE
            for (i in state.pageRects.indices) {
                val d = state.pageRects[i].distanceTo(content)
                if (d < bestD) {
                    bestD = d
                    pi = i
                }
            }
        }
        val index = pi ?: return null
        val pr = state.pageRects.getOrNull(index) ?: return null
        return index to Pt((content.x - pr.left).coerceIn(0.0, pr.w), (content.y - pr.top).coerceIn(0.0, pr.h))
    }

    companion object {
        const val DRAG_SLOP = 14.0
        const val DOUBLE_TAP_MS = 350L
        const val DOUBLE_TAP_SLOP = 32.0
        const val CARET_MARGIN = 24.0
        const val BURST_IDLE_MS = 2000L
    }
}
