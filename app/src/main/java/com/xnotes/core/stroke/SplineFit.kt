package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt

/**
 * Snaps a freehand open stroke to one smooth curve: a Catmull-Rom spline threaded through a few
 * evenly-spaced, lightly-smoothed anchor points sampled along the stroke. Always a single flowing
 * stroke (never a chain of pieces), and unlike a single Bézier it follows a wide C or an S because
 * it interpolates points taken from the curve itself.
 *
 * Returns null when the spline can't follow the stroke (a complex scribble the few anchors miss),
 * so a stroke that isn't a simple curve is left as ink rather than forced into a wrong one.
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like [StrokeEngine]. The
 * curve is handed back sampled as a dense polyline (the renderer has no spline primitive).
 */
object SplineFit {

    /** Light [1 2 1]/4 smoothing passes over the resampled stroke before anchors are taken. */
    private const val SMOOTH_ITERS = 2

    /**
     * Fit [points] (an arc-length resample) and return the spline sampled to a dense polyline:
     * [anchorCount] anchors, [samplesPerSeg] points per Catmull-Rom segment. Null when the result
     * strays from the stroke by more than [maxError] (leave it as ink).
     */
    fun fitSampled(points: List<Pt>, anchorCount: Int, samplesPerSeg: Int, maxError: Double): List<Pt>? {
        val pts = dedupe(points)
        if (pts.size < anchorCount || pts.size < 4) return null
        val anchors = pickAnchors(smooth(pts), anchorCount)
        val curve = catmullRom(anchors, samplesPerSeg)
        if (curve.size < 3) return null
        if (maxDeviation(pts, curve) > maxError) return null
        return curve
    }

    /** [1 2 1]/4 smoothing with the endpoints pinned, repeated [SMOOTH_ITERS] times. */
    private fun smooth(pts: List<Pt>): List<Pt> {
        var cur = pts
        repeat(SMOOTH_ITERS) {
            val next = ArrayList<Pt>(cur.size)
            next.add(cur.first())
            for (i in 1 until cur.size - 1) next.add((cur[i - 1] + cur[i] * 2.0 + cur[i + 1]) * 0.25)
            next.add(cur.last())
            cur = next
        }
        return cur
    }

    /** [count] anchors evenly spaced along [pts] by index, including both endpoints. */
    private fun pickAnchors(pts: List<Pt>, count: Int): List<Pt> {
        val n = pts.size
        return (0 until count).map { k -> pts[(k.toLong() * (n - 1) / (count - 1)).toInt()] }
    }

    /** Sample a Catmull-Rom spline through [anchors], with reflected phantom endpoints. */
    private fun catmullRom(anchors: List<Pt>, samplesPerSeg: Int): List<Pt> {
        val n = anchors.size
        val out = ArrayList<Pt>((n - 1) * samplesPerSeg + 1)
        out.add(anchors[0])
        for (i in 0 until n - 1) {
            val p0 = if (i == 0) anchors[0] * 2.0 - anchors[1] else anchors[i - 1]
            val p1 = anchors[i]
            val p2 = anchors[i + 1]
            val p3 = if (i + 2 >= n) anchors[n - 1] * 2.0 - anchors[n - 2] else anchors[i + 2]
            for (s in 1..samplesPerSeg) out.add(segment(p0, p1, p2, p3, s.toDouble() / samplesPerSeg))
        }
        return out
    }

    /** Centripetal-tension Catmull-Rom point at [t] in [0,1] on the p1->p2 segment. */
    private fun segment(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Double): Pt {
        val t2 = t * t
        val t3 = t2 * t
        val a = p1 * 2.0
        val b = (p2 - p0) * t
        val c = (p0 * 2.0 - p1 * 5.0 + p2 * 4.0 - p3) * t2
        val d = (p1 * 3.0 - p0 - p2 * 3.0 + p3) * t3
        return (a + b + c + d) * 0.5
    }

    private fun maxDeviation(pts: List<Pt>, curve: List<Pt>): Double {
        var max = 0.0
        for (p in pts) {
            var nearest = Double.MAX_VALUE
            for (j in 0 until curve.size - 1) {
                val d = Geometry.distancePointToSegment(p, curve[j], curve[j + 1])
                if (d < nearest) nearest = d
            }
            if (nearest > max) max = nearest
        }
        return max
    }

    private fun dedupe(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(points.size)
        for (p in points) if (out.isEmpty() || out.last().distanceTo(p) > 1e-9) out.add(p)
        return out
    }
}
