package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer

/** A pasted or inserted bitmap (spec 02 §5.2). Holds the encoded [image] source (decoded on demand
 *  by the renderer) and a quarter-turn [orientation]; resize is aspect-locked. */
class ImageItem(
    var image: ImageData,
    var rect: Rect,
    var orientation: Int = 0,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true

    override fun paint(r: Renderer) = r.drawImage(image, rect, orientation)

    override fun bounds(): Rect = rect

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(Pt(cx, cy)) <= radius

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) rect = handle.rect
    }

    override fun snapshotGeometry(): GeometrySnapshot = ImageSnapshot(rect)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is ImageSnapshot) rect = snap.rect
    }

    /** Images never rotate, so the transform is an axis-aligned scale: map the rect's two opposite
     *  corners and rebuild it (a non-uniform scale stretches the drawn bitmap into the new rect). */
    override fun applyTransform(t: Affine) {
        rect = Rect.fromPoints(t.apply(rect.topLeft), t.apply(Pt(rect.right, rect.bottom)))
    }

    companion object {
        const val KIND = "image"
    }
}

/** Snapshot of an image's transformable geometry. */
private data class ImageSnapshot(val rect: Rect) : GeometrySnapshot
