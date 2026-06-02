package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer

/**
 * A wrapped plain-text box (spec 02 §5.3). [width] is the wrap width; the box
 * grows to fit its text, so [height] is a *reserved minimum* — the rendered box
 * is `max(height, content height)` tall and never clips. A box created by a tap
 * has `height == 0` (pure auto-height, as before); one dragged out reserves the
 * dragged height. The [measurer] lays out text identically for bounds and paint.
 */
class TextItem(
    var pos: Pt,
    var width: Double = DEFAULT_WIDTH,
    var height: Double = 0.0,
    var text: String = "",
    var rgba: Rgba = DEFAULT_COLOR,
    var pointSize: Double = DEFAULT_POINT_SIZE,
    var face: FontFace = DEFAULT_FACE,
    private val measurer: TextMeasurer,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true

    val font get() = FontSpec(pointSize, face)

    /** Height the current text needs at the current width (empty ⇒ one line). */
    fun contentHeight(): Double = measurer.measure(text.ifEmpty { " " }, font, width, FLAGS).h

    override fun bounds(): Rect = Rect(pos.x, pos.y, width, maxOf(height, contentHeight()))

    override fun paint(r: Renderer) {
        if (text.isEmpty()) return
        r.drawText(text, bounds(), font, rgba, FLAGS)
    }

    override fun translate(dx: Double, dy: Double) {
        pos = Pt(pos.x + dx, pos.y + dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = TextHandle(pos, width, height)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is TextHandle) {
            pos = handle.pos
            width = handle.width
            height = handle.height
        }
    }

    companion object {
        const val KIND = "text"
        const val DEFAULT_WIDTH = 300.0
        const val DEFAULT_POINT_SIZE = 13.0
        val DEFAULT_FACE = FontFace.MONO
        val DEFAULT_COLOR = Rgba(236, 236, 236, 255)
        val FLAGS = TextFlags(wordWrap = true, alignLeft = true, alignTop = true)
    }
}

/** A snapshot of a text box's restylable properties (colour, size, face) for undo. */
data class TextStyle(val rgba: Rgba, val pointSize: Double, val face: FontFace) {
    fun applyTo(t: TextItem) {
        t.rgba = rgba
        t.pointSize = pointSize
        t.face = face
    }

    companion object {
        fun of(t: TextItem) = TextStyle(t.rgba, t.pointSize, t.face)
    }
}
