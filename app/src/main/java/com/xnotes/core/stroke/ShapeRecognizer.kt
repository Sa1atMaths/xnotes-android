package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.tools.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A geometric shape inferred from a freehand stroke (the "hold to snap" gesture).
 * [start]/[end] are the same two points a [com.xnotes.core.model.ShapeItem] takes:
 * the two endpoints for [ShapeKind.LINE], or opposite corners of the bounding box
 * for the closed kinds.
 */
data class RecognizedShape(val kind: ShapeKind, val start: Pt, val end: Pt)

/**
 * Classifies a freehand stroke into one of a few simple shapes, or `null` when it
 * is not a confident match (in which case the caller leaves the stroke as ink).
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like
 * [StrokeEngine]. Works in page-local content px; every threshold is a fraction of
 * the stroke's bounding-box diagonal, so recognition is scale- (and zoom-)
 * independent.
 *
 * Two model limitations to keep in mind when reading the results:
 *  - [ShapeItem][com.xnotes.core.model.ShapeItem] geometry is axis-aligned, so a
 *    tilted rectangle/ellipse necessarily snaps to its upright bounding box.
 *  - A [ShapeKind.TRIANGLE] always renders as an upward isosceles triangle inscribed
 *    in its box, so the recognizer cannot preserve a triangle's orientation.
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

    /** Points the closed loop is resampled to before measuring roundness / corners. */
    private const val RESAMPLE_N = 64

    /** RMS normalized ellipse residual at or under this is an ellipse/circle. */
    private const val ELLIPSE_RESIDUAL_FRAC = 0.11

    /** Enclosed-area / bbox-area at or above this (with 4 corners) is a rectangle. */
    private const val RECT_FILL_MIN = 0.78

    /** Turn (direction change) at a vertex at or above this counts as a corner. */
    private const val CORNER_ANGLE_DEG = 50.0

    private val CORNER_ANGLE_RAD = CORNER_ANGLE_DEG * PI / 180.0

    /** Recognize from raw stroke samples (the page-local positions are what matter). */
    fun recognize(samples: List<Sample>): RecognizedShape? = recognizePoints(samples.map { it.pos })

    /** Recognize from a bare list of page-local points; drives the unit tests directly. */
    fun recognizePoints(points: List<Pt>): RecognizedShape? {
        if (points.size < MIN_POINTS) return null
        val bbox = Rect.bounding(points)
        val diag = hypot(bbox.w, bbox.h)
        if (diag < MIN_DIAGONAL) return null

        val first = points.first()
        val last = points.last()
        val closed = first.distanceTo(last) <= CLOSED_GAP_FRAC * diag

        if (!closed) {
            // Open path: the only thing we snap is a straight line that hugs its chord.
            val chord = first.distanceTo(last)
            if (chord > 1e-6) {
                val maxDev = points.maxOf { Geometry.distancePointToSegment(it, first, last) }
                if (maxDev / chord <= LINE_DEV_FRAC) return RecognizedShape(ShapeKind.LINE, first, last)
            }
            return null
        }

        // Closed path: resample evenly around the loop (including the closing segment) so the
        // roundness/corner tests are insensitive to where the stroke happened to start and to
        // uneven sampling speed.
        val loop = resampleClosed(points, RESAMPLE_N)
        val topLeft = bbox.topLeft
        val bottomRight = Pt(bbox.right, bbox.bottom)

        if (ellipseResidual(loop, bbox) <= ELLIPSE_RESIDUAL_FRAC) {
            return RecognizedShape(ShapeKind.ELLIPSE, topLeft, bottomRight)
        }

        val corners = cornerCount(loop)
        val bboxArea = bbox.w * bbox.h
        val fill = if (bboxArea > 1e-9) polygonArea(loop) / bboxArea else 0.0
        return when {
            // Allow a 5th corner for the spurious vertex a stroke can leave mid-edge where it
            // started/ended; the fill ratio separates a real rectangle from a thin/concave quad.
            corners in 4..5 && fill >= RECT_FILL_MIN -> RecognizedShape(ShapeKind.RECTANGLE, topLeft, bottomRight)
            corners == 3 -> RecognizedShape(ShapeKind.TRIANGLE, topLeft, bottomRight)
            else -> null
        }
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

    /**
     * Number of distinct corners on the cyclic [loop]. The turn at each point is the angle
     * between the chord coming in and the chord going out (smoothed over a window so sampling
     * noise doesn't fake corners); a run of points whose turn clears [CORNER_ANGLE_RAD] is one
     * corner. The loop is rotated to start at its straightest point so no corner straddles the
     * seam, and edges between corners drop below the threshold and separate the runs.
     */
    private fun cornerCount(loop: List<Pt>): Int {
        val n = loop.size
        if (n < 8) return 0
        val k = (n / 12).coerceAtLeast(2)
        val turns = DoubleArray(n) { i ->
            val prev = loop[(i - k + n) % n]
            val cur = loop[i]
            val next = loop[(i + k) % n]
            angleBetween(cur - prev, next - cur)
        }
        // Start the scan at the straightest point (clearly mid-edge) so a corner never wraps.
        var minIdx = 0
        for (i in 1 until n) if (turns[i] < turns[minIdx]) minIdx = i
        var count = 0
        var i = 0
        while (i < n) {
            val turn = turns[(minIdx + i) % n]
            if (turn >= CORNER_ANGLE_RAD) {
                count++
                i++
                while (i < n && turns[(minIdx + i) % n] >= CORNER_ANGLE_RAD) i++
            } else {
                i++
            }
        }
        return count
    }

    /** Angle in radians between two vectors; 0 when either is degenerate. */
    private fun angleBetween(u: Pt, v: Pt): Double {
        val lu = u.length()
        val lv = v.length()
        if (lu < 1e-9 || lv < 1e-9) return 0.0
        val cos = ((u.x * v.x + u.y * v.y) / (lu * lv)).coerceIn(-1.0, 1.0)
        return acos(cos)
    }

    /** Shoelace area of a closed polygon (absolute, so winding direction doesn't matter). */
    private fun polygonArea(poly: List<Pt>): Double {
        if (poly.size < 3) return 0.0
        var sum = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
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
