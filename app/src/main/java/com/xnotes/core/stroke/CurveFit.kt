package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import kotlin.math.abs

/**
 * Fits a smooth cubic Bézier spline to a freehand open path (Schneider, "An Algorithm for
 * Automatically Fitting Digitized Curves", Graphics Gems, 1990): fit one cubic by least
 * squares with fixed end tangents, and if it strays too far, split at the worst point and
 * recurse. The result is a flowing curve that follows the stroke with the hand shake removed.
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like [StrokeEngine].
 * Used by [ShapeRecognizer] to snap a held C/S stroke to a clean curve; the renderer has no
 * cubic primitive, so the spline is sampled back to a dense polyline.
 */
object CurveFit {

    /** A cubic Bézier: anchors [p0]/[p3] with control handles [p1]/[p2]. */
    class Cubic(val p0: Pt, val p1: Pt, val p2: Pt, val p3: Pt)

    /** Newton-Raphson reparameterization passes before a segment is split. */
    private const val MAX_ITER = 4

    /** Fit [points] and return the spline sampled to [samplesPerSeg] points per segment. */
    fun fitSampled(points: List<Pt>, errorTol: Double, samplesPerSeg: Int): List<Pt> {
        val beziers = fit(points, errorTol)
        if (beziers.isEmpty()) return emptyList()
        val out = ArrayList<Pt>(beziers.size * samplesPerSeg + 1)
        out.add(beziers.first().p0)
        for (b in beziers) {
            for (i in 1..samplesPerSeg) out.add(eval(b, i.toDouble() / samplesPerSeg))
        }
        return out
    }

    /** Fit a cubic Bézier spline to [points]; returns one or more end-joined segments. */
    fun fit(points: List<Pt>, errorTol: Double): List<Cubic> {
        val pts = dedupe(points)
        if (pts.size < 2) return emptyList()
        if (pts.size == 2) return listOf(lineCubic(pts[0], pts[1]))
        val out = ArrayList<Cubic>()
        fitCubic(pts, 0, pts.size - 1, leftTangent(pts), rightTangent(pts), errorTol * errorTol, out)
        return out
    }

    private fun fitCubic(pts: List<Pt>, first: Int, last: Int, tHat1: Pt, tHat2: Pt, errorSq: Double, out: MutableList<Cubic>) {
        if (last - first == 1) {
            val dist = pts[first].distanceTo(pts[last]) / 3.0
            out.add(Cubic(pts[first], pts[first] + tHat1 * dist, pts[last] + tHat2 * dist, pts[last]))
            return
        }
        var u = chordLengthParameterize(pts, first, last)
        var bez = generateBezier(pts, first, last, u, tHat1, tHat2)
        var (maxErr, split) = computeMaxError(pts, first, last, bez, u)
        if (maxErr < errorSq) {
            out.add(bez)
            return
        }
        // Close: try to improve the parameterization before resorting to a split. (4·err)² = 16·errSq.
        if (maxErr < errorSq * 16.0) {
            for (i in 0 until MAX_ITER) {
                val uPrime = reparameterize(pts, first, last, u, bez)
                bez = generateBezier(pts, first, last, uPrime, tHat1, tHat2)
                val r = computeMaxError(pts, first, last, bez, uPrime)
                maxErr = r.first
                split = r.second
                if (maxErr < errorSq) {
                    out.add(bez)
                    return
                }
                u = uPrime
            }
        }
        val tHatC = centerTangent(pts, split)
        fitCubic(pts, first, split, tHat1, tHatC, errorSq, out)
        fitCubic(pts, split, last, tHatC * -1.0, tHat2, errorSq, out)
    }

    /** Least-squares cubic with the two end tangents held fixed (Graphics Gems). */
    private fun generateBezier(pts: List<Pt>, first: Int, last: Int, u: DoubleArray, tHat1: Pt, tHat2: Pt): Cubic {
        val n = last - first + 1
        val p0 = pts[first]
        val p3 = pts[last]
        var c00 = 0.0
        var c01 = 0.0
        var c11 = 0.0
        var x0 = 0.0
        var x1 = 0.0
        for (i in 0 until n) {
            val ui = u[i]
            val a0 = tHat1 * b1(ui)
            val a1 = tHat2 * b2(ui)
            c00 += dot(a0, a0)
            c01 += dot(a0, a1)
            c11 += dot(a1, a1)
            val tmp = pts[first + i] - (p0 * (b0(ui) + b1(ui)) + p3 * (b2(ui) + b3(ui)))
            x0 += dot(a0, tmp)
            x1 += dot(a1, tmp)
        }
        val detC0C1 = c00 * c11 - c01 * c01
        var alphaL = 0.0
        var alphaR = 0.0
        if (abs(detC0C1) > 1e-12) {
            alphaL = (x0 * c11 - x1 * c01) / detC0C1
            alphaR = (c00 * x1 - c01 * x0) / detC0C1
        }
        val segLen = p0.distanceTo(p3)
        val epsilon = 1e-6 * segLen
        if (alphaL < epsilon || alphaR < epsilon) {
            // Degenerate fit: fall back to the Wu/Barsky heuristic (handles a third of the chord out).
            val dist = segLen / 3.0
            return Cubic(p0, p0 + tHat1 * dist, p3 + tHat2 * dist, p3)
        }
        return Cubic(p0, p0 + tHat1 * alphaL, p3 + tHat2 * alphaR, p3)
    }

    private fun reparameterize(pts: List<Pt>, first: Int, last: Int, u: DoubleArray, bez: Cubic): DoubleArray =
        DoubleArray(last - first + 1) { i -> newtonRaphson(bez, pts[first + i], u[i]) }

    /** One Newton-Raphson step toward the curve parameter nearest [p]. */
    private fun newtonRaphson(q: Cubic, p: Pt, u: Double): Double {
        val qu = eval(q, u)
        val q1 = arrayOf((q.p1 - q.p0) * 3.0, (q.p2 - q.p1) * 3.0, (q.p3 - q.p2) * 3.0)
        val q2 = arrayOf((q1[1] - q1[0]) * 2.0, (q1[2] - q1[1]) * 2.0)
        val quP = q1[0] * ((1 - u) * (1 - u)) + q1[1] * (2 * (1 - u) * u) + q1[2] * (u * u)
        val quPP = q2[0] * (1 - u) + q2[1] * u
        val den = dot(quP, quP) + dot(qu - p, quPP)
        if (den == 0.0) return u
        return u - dot(qu - p, quP) / den
    }

    /** Evaluate the cubic at [t] in [0,1]. */
    fun eval(b: Cubic, t: Double): Pt {
        val mt = 1.0 - t
        return b.p0 * (mt * mt * mt) + b.p1 * (3.0 * mt * mt * t) + b.p2 * (3.0 * mt * t * t) + b.p3 * (t * t * t)
    }

    private fun computeMaxError(pts: List<Pt>, first: Int, last: Int, bez: Cubic, u: DoubleArray): Pair<Double, Int> {
        var maxDistSq = 0.0
        var split = (first + last) / 2
        for (i in (first + 1) until last) {
            val d = eval(bez, u[i - first]) - pts[i]
            val distSq = dot(d, d)
            if (distSq >= maxDistSq) {
                maxDistSq = distSq
                split = i
            }
        }
        return maxDistSq to split
    }

    private fun chordLengthParameterize(pts: List<Pt>, first: Int, last: Int): DoubleArray {
        val u = DoubleArray(last - first + 1)
        for (i in (first + 1)..last) u[i - first] = u[i - first - 1] + pts[i].distanceTo(pts[i - 1])
        val total = u[last - first]
        if (total > 0.0) for (i in 1..(last - first)) u[i] /= total
        return u
    }

    private fun leftTangent(pts: List<Pt>): Pt = (pts[1] - pts[0]).normalized()
    private fun rightTangent(pts: List<Pt>): Pt = (pts[pts.size - 2] - pts[pts.size - 1]).normalized()
    private fun centerTangent(pts: List<Pt>, i: Int): Pt = (pts[i - 1] - pts[i + 1]).normalized()

    private fun lineCubic(p0: Pt, p1: Pt): Cubic {
        val third = (p1 - p0) * (1.0 / 3.0)
        return Cubic(p0, p0 + third, p0 + third * 2.0, p1)
    }

    private fun dedupe(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(points.size)
        for (p in points) if (out.isEmpty() || out.last().distanceTo(p) > 1e-9) out.add(p)
        return out
    }

    private fun dot(a: Pt, b: Pt): Double = a.x * b.x + a.y * b.y
    private fun b0(t: Double): Double = (1 - t) * (1 - t) * (1 - t)
    private fun b1(t: Double): Double = 3 * (1 - t) * (1 - t) * t
    private fun b2(t: Double): Double = 3 * (1 - t) * t * t
    private fun b3(t: Double): Double = t * t * t
}
