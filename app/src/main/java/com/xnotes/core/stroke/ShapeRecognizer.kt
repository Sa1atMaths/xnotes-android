package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.tools.ShapeKind
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A geometric shape inferred from a freehand stroke (the "hold to snap" gesture).
 * [start]/[end] are opposite corners of the bounding box (or the two endpoints for
 * [ShapeKind.LINE]); [vertices] carries the corner list (content px) for the
 * [ShapeKind.POLYGON] and [ShapeKind.POLYLINE] kinds and is null for the rest.
 */
data class RecognizedShape(
    val kind: ShapeKind,
    val start: Pt,
    val end: Pt,
    val vertices: List<Pt>? = null,
)

/**
 * Classifies a freehand stroke into a clean shape, or `null` when it is not a confident
 * match (the caller then leaves the stroke as ink).
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like [StrokeEngine].
 * Works in page-local content px; every threshold is a fraction of the stroke's
 * bounding-box diagonal, so recognition is scale- (and zoom-) independent.
 *
 * Shapes recognized:
 *  - open straight stroke -> [ShapeKind.LINE]
 *  - open multi-segment zig-zag -> [ShapeKind.POLYLINE] (vertices preserved)
 *  - any other smooth open stroke (a C, an S, ...) -> [ShapeKind.CURVE] (one or more fitted
 *    elliptical arcs, sampled to a dense polyline)
 *  - closed round blob -> [ShapeKind.ELLIPSE] (snapped to a circle when nearly round)
 *  - closed n-gon with sharp corners -> [ShapeKind.RECTANGLE] when it is an upright box,
 *    else [ShapeKind.POLYGON] (vertices preserved, including 3-corner triangles)
 *
 * Corners drive the closed-shape decision: a smooth outline (no sharp turns) is the only
 * thing that becomes an ellipse, so a hexagon stays a polygon and a circle never does.
 */
object ShapeRecognizer {

    /** Fewer samples than this is a tap or tick, never a shape. */
    private const val MIN_POINTS = 8

    /** Bounding-box diagonal (content px) below which the stroke is too small to snap. */
    private const val MIN_DIAGONAL = 24.0

    /** |first-last| / diagonal at or under this counts the path as closed. */
    private const val CLOSED_GAP_FRAC = 0.22

    /** max perpendicular deviation / chord length at or under this is a straight line. */
    private const val LINE_DEV_FRAC = 0.08

    /** Points the path is resampled to before measuring roundness / corners. */
    private const val RESAMPLE_N = 64

    /** RMS normalized ellipse residual at or under this is an ellipse/circle. */
    private const val ELLIPSE_RESIDUAL_FRAC = 0.11

    /** Turn (direction change) at a vertex at or above this counts as a corner. */
    private const val CORNER_ANGLE_DEG = 50.0

    private val CORNER_ANGLE_RAD = CORNER_ANGLE_DEG * PI / 180.0

    /** A corner candidate must also turn this sharply over a tight window, else it is a smooth bend. */
    private const val SHARP_CORNER_DEG = 42.0

    private val SHARP_CORNER_RAD = SHARP_CORNER_DEG * PI / 180.0

    /** Shorter axis / longer axis at or above this snaps a recognized ellipse to a circle. */
    private const val CIRCLE_ASPECT_MIN = 0.80

    /** A 4-gon whose corners each sit within this·diagonal of a distinct bbox corner is an upright rectangle. */
    private const val RECT_CORNER_FRAC = 0.12

    /** Max edge bow / diagonal for a polygon/polyline edge to count as straight. */
    private const val EDGE_DEV_FRAC = 0.06

    /** More inferred corners than this means a noisy blob, not a drawn polygon. */
    private const val MAX_POLY_VERTS = 12

    /** Arc-fit error tolerance for a snapped curve (when to split into another arc), as a
     *  fraction of the bbox diagonal. */
    private const val CURVE_FIT_TOL_FRAC = 0.05

    /** Points sampled per fitted Bézier segment when a curve is turned back into a polyline. */
    private const val CURVE_SAMPLES_PER_SEG = 16

    /** Recognize from raw stroke samples (the page-local positions are what matter). */
    fun recognize(samples: List<Sample>): RecognizedShape? = recognizePoints(samples.map { it.pos })

    /** Recognize from a bare list of page-local points; drives the unit tests directly. */
    fun recognizePoints(points: List<Pt>): RecognizedShape? {
        if (points.size < MIN_POINTS) return null
        val bbox = Rect.bounding(points)
        val diag = hypot(bbox.w, bbox.h)
        if (diag < MIN_DIAGONAL) return null

        val closed = points.first().distanceTo(points.last()) <= CLOSED_GAP_FRAC * diag
        return if (closed) recognizeClosed(points, bbox, diag) else recognizeOpen(points, diag)
    }

    // --- open paths: straight line or zig-zag polyline ---

    private fun recognizeOpen(points: List<Pt>, diag: Double): RecognizedShape? {
        val first = points.first()
        val last = points.last()
        // A stroke hugging its chord snaps to a straight line.
        val chord = first.distanceTo(last)
        if (chord > 1e-6) {
            val maxDev = points.maxOf { Geometry.distancePointToSegment(it, first, last) }
            if (maxDev / chord <= LINE_DEV_FRAC) return RecognizedShape(ShapeKind.LINE, first, last)
        }
        val path = resample(points, RESAMPLE_N)
        // A multi-segment zig-zag has sharp corners joined by straight runs -> polyline.
        val peaks = cornerPeaks(path, wrap = false)
        if (peaks.isNotEmpty() && peaks.size <= MAX_POLY_VERTS) {
            val vertIdx = listOf(0) + peaks + listOf(path.size - 1)
            if (edgesStraight(path, vertIdx, wrap = false, diag)) {
                val verts = vertIdx.map { path[it] }
                val box = Rect.bounding(verts)
                return RecognizedShape(ShapeKind.POLYLINE, box.topLeft, Pt(box.right, box.bottom), verts)
            }
        }
        // Otherwise a smooth freehand curve (a C, an S, any flowing open stroke): snap it to a
        // clean elliptical arc, or a chain of arcs where one ellipse can't span it (e.g. an S).
        return curveFrom(path, diag)
    }

    private fun curveFrom(path: List<Pt>, diag: Double): RecognizedShape? {
        val curve = EllipseArcFit.fitSampled(path, CURVE_FIT_TOL_FRAC * diag, CURVE_SAMPLES_PER_SEG)
        if (curve.size < 3) return null
        val box = Rect.bounding(curve)
        return RecognizedShape(ShapeKind.CURVE, box.topLeft, Pt(box.right, box.bottom), curve)
    }

    // --- closed paths: polygon (incl. rectangle) or ellipse/circle ---

    private fun recognizeClosed(points: List<Pt>, bbox: Rect, diag: Double): RecognizedShape? {
        // Resample evenly around the loop (including the closing segment) so the corner/roundness
        // tests are insensitive to where the stroke started and to uneven sampling speed.
        val loop = resampleClosed(points, RESAMPLE_N)
        val topLeft = bbox.topLeft
        val bottomRight = Pt(bbox.right, bbox.bottom)

        // Sharp, straight-edged corners win: that is what tells a hexagon from a circle.
        val peaks = cornerPeaks(loop, wrap = true)
        if (peaks.size in 3..MAX_POLY_VERTS && edgesStraight(loop, peaks, wrap = true, diag)) {
            val verts = peaks.map { loop[it] }
            return if (verts.size == 4 && isAxisAlignedRect(verts, bbox, diag)) {
                RecognizedShape(ShapeKind.RECTANGLE, topLeft, bottomRight)
            } else {
                RecognizedShape(ShapeKind.POLYGON, topLeft, bottomRight, verts)
            }
        }

        // No clean corners: a round outline snaps to an ellipse (a circle when nearly round).
        if (ellipseResidual(loop, bbox) <= ELLIPSE_RESIDUAL_FRAC) return ellipseOrCircle(bbox)
        return null
    }

    /** Near-round ellipse -> a true circle (centred square box); a clear oval stays an ellipse. */
    private fun ellipseOrCircle(bbox: Rect): RecognizedShape {
        val longAxis = max(bbox.w, bbox.h)
        val shortAxis = min(bbox.w, bbox.h)
        val aspect = if (longAxis > 1e-9) shortAxis / longAxis else 1.0
        if (aspect < CIRCLE_ASPECT_MIN) {
            return RecognizedShape(ShapeKind.ELLIPSE, bbox.topLeft, Pt(bbox.right, bbox.bottom))
        }
        val r = (bbox.w + bbox.h) / 4.0
        val cx = bbox.centerX
        val cy = bbox.centerY
        return RecognizedShape(ShapeKind.ELLIPSE, Pt(cx - r, cy - r), Pt(cx + r, cy + r))
    }

    /** True if the four [verts] each sit near a distinct corner of [bbox] (an upright rectangle). */
    private fun isAxisAlignedRect(verts: List<Pt>, bbox: Rect, diag: Double): Boolean {
        val corners = listOf(
            Pt(bbox.left, bbox.top), Pt(bbox.right, bbox.top),
            Pt(bbox.right, bbox.bottom), Pt(bbox.left, bbox.bottom),
        )
        val tol = RECT_CORNER_FRAC * diag
        val used = BooleanArray(corners.size)
        for (v in verts) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (c in corners.indices) {
                if (used[c]) continue
                val d = v.distanceTo(corners[c])
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
            if (best < 0 || bestD > tol) return false
            used[best] = true
        }
        return true
    }

    /**
     * Indices of the corner peaks along [path]. The turn at each point is the angle between the
     * chord coming in and the chord going out (over a window so sampling noise doesn't fake
     * corners); a run of points clearing [CORNER_ANGLE_RAD] is one corner, reported at its
     * sharpest point. A closed loop ([wrap]) starts the scan at its straightest point so no corner
     * straddles the seam; an open path never treats its two ends as corners.
     */
    private fun cornerPeaks(path: List<Pt>, wrap: Boolean): List<Int> {
        val n = path.size
        if (n < 8) return emptyList()
        val k = (n / 12).coerceAtLeast(2)
        val turns = DoubleArray(n) { i ->
            if (!wrap && (i - k < 0 || i + k >= n)) {
                0.0
            } else {
                val prev = path[(i - k + n) % n]
                val cur = path[i]
                val next = path[(i + k) % n]
                angleBetween(cur - prev, next - cur)
            }
        }
        val startAt = if (wrap) {
            var minIdx = 0
            for (i in 1 until n) if (turns[i] < turns[minIdx]) minIdx = i
            minIdx
        } else {
            0
        }
        val peaks = ArrayList<Int>()
        var i = 0
        while (i < n) {
            val gi = (startAt + i) % n
            if (turns[gi] >= CORNER_ANGLE_RAD) {
                var bestIdx = gi
                var bestTurn = turns[gi]
                i++
                while (i < n) {
                    val gj = (startAt + i) % n
                    if (turns[gj] < CORNER_ANGLE_RAD) break
                    if (turns[gj] > bestTurn) {
                        bestTurn = turns[gj]
                        bestIdx = gj
                    }
                    i++
                }
                peaks.add(bestIdx)
            } else {
                i++
            }
        }
        // Keep only peaks that are genuinely sharp (a localized turn), dropping smooth bends that
        // a wide window can read as corners. This is what separates a tight S-curve from a zig-zag.
        peaks.retainAll { isSharpCorner(path, it, wrap) }
        peaks.sort()
        return peaks
    }

    /** True if the turn at [i] is sharp over a tight window (a real corner, not a flowing bend). */
    private fun isSharpCorner(path: List<Pt>, i: Int, wrap: Boolean): Boolean {
        val n = path.size
        val kt = 2
        if (!wrap && (i - kt < 0 || i + kt >= n)) return true // at an open end; endpoints handled elsewhere
        val prev = path[(i - kt + n) % n]
        val cur = path[i]
        val next = path[(i + kt) % n]
        return angleBetween(cur - prev, next - cur) >= SHARP_CORNER_RAD
    }

    /** True if every edge between consecutive vertices stays within [EDGE_DEV_FRAC]·diag of straight. */
    private fun edgesStraight(path: List<Pt>, vertIdx: List<Int>, wrap: Boolean, diag: Double): Boolean {
        val n = path.size
        val m = vertIdx.size
        if (m < 2) return false
        val tol = EDGE_DEV_FRAC * diag
        val edges = if (wrap) m else m - 1
        for (e in 0 until edges) {
            val i0 = vertIdx[e]
            val i1 = vertIdx[(e + 1) % m]
            val a = path[i0]
            val b = path[i1]
            var j = i0
            while (j != i1) {
                if (Geometry.distancePointToSegment(path[j], a, b) > tol) return false
                j = (j + 1) % n
                if (!wrap && j == 0) break // an open path never wraps past its end
            }
        }
        return true
    }

    /** RMS of `sqrt((x/rx)^2 + (y/ry)^2) - 1` about the bbox centre: 0 on a perfect inscribed ellipse. */
    private fun ellipseResidual(loop: List<Pt>, bbox: Rect): Double {
        val cx = bbox.center.x
        val cy = bbox.center.y
        val rx = (bbox.w / 2.0).coerceAtLeast(1e-6)
        val ry = (bbox.h / 2.0).coerceAtLeast(1e-6)
        var sumSq = 0.0
        for (p in loop) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            val d = sqrt(nx * nx + ny * ny) - 1.0
            sumSq += d * d
        }
        return sqrt(sumSq / loop.size)
    }

    /** Angle in radians between two vectors; 0 when either is degenerate. */
    private fun angleBetween(u: Pt, v: Pt): Double {
        val lu = u.length()
        val lv = v.length()
        if (lu < 1e-9 || lv < 1e-9) return 0.0
        val cos = ((u.x * v.x + u.y * v.y) / (lu * lv)).coerceIn(-1.0, 1.0)
        return acos(cos)
    }

    /** Resample [points] to [n] points spaced evenly around the closed loop (last joins first). */
    private fun resampleClosed(points: List<Pt>, n: Int): List<Pt> =
        resample(points + points.first(), n + 1).let { if (it.size > n) it.subList(0, n) else it }

    /** Classic arc-length resampling to exactly [n] evenly-spaced points (spec-style). */
    private fun resample(points: List<Pt>, n: Int): List<Pt> {
        if (points.size <= 1 || n <= 1) return points
        val total = pathLength(points)
        if (total < 1e-9) return List(n) { points.first() }
        val step = total / (n - 1)
        val out = ArrayList<Pt>(n)
        out.add(points.first())
        var prev = points.first()
        var accum = 0.0
        var i = 1
        while (i < points.size && out.size < n) {
            val curr = points[i]
            val seg = prev.distanceTo(curr)
            if (seg < 1e-12) {
                i++
                continue
            }
            if (accum + seg >= step) {
                val t = (step - accum) / seg
                val np = Pt(prev.x + t * (curr.x - prev.x), prev.y + t * (curr.y - prev.y))
                out.add(np)
                prev = np
                accum = 0.0
            } else {
                accum += seg
                prev = curr
                i++
            }
        }
        while (out.size < n) out.add(points.last())
        return out
    }

    private fun pathLength(points: List<Pt>): Double {
        var len = 0.0
        for (i in 1 until points.size) len += points[i - 1].distanceTo(points[i])
        return len
    }
}
