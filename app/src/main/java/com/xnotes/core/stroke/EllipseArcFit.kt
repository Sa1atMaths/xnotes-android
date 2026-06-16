package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Snaps a freehand open stroke to a clean **elliptical** arc, or a chain of them: fit the best
 * ellipse to the whole stroke (direct least-squares, Halir & Flusser 1998, the numerically
 * stable form of Fitzgibbon's ellipse-specific fit), and where one ellipse cannot follow the
 * stroke (an S has an inflection no single ellipse spans) split at the worst point and fit each
 * side. A C becomes one arc, an S two, and so on.
 *
 * Pure and deterministic so it can be unit-tested on the plain JVM, like [StrokeEngine]. The
 * renderer has no rotated-ellipse or arc primitive, so each arc is ray-cast from its ellipse
 * centre and handed back sampled as a dense polyline.
 */
object EllipseArcFit {

    /** Fewer points than this can't pin down an ellipse, so the run is treated as straight. */
    private const val MIN_SEG_PTS = 6

    /** A run counts as straight only if it bows less than this fraction of its own chord. */
    private const val STRAIGHT_REL = 0.04

    /** Conic A x² + B xy + C y² + D x + E y + F = 0. */
    private class Conic(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double,
    ) {
        fun eval(x: Double, y: Double) = a * x * x + b * x * y + c * y * y + d * x + e * y + f

        /** Centre of the conic (where its gradient vanishes); null when it is not a proper ellipse. */
        fun center(): Pt? {
            val den = 4.0 * a * c - b * b
            if (abs(den) < 1e-12) return null
            return Pt((b * e - 2.0 * c * d) / den, (b * d - 2.0 * a * e) / den)
        }
    }

    /** Fit [points] and return the arc chain sampled to [samplesPerArc] points per arc. */
    fun fitSampled(points: List<Pt>, errorTol: Double, samplesPerArc: Int): List<Pt> {
        val pts = dedupe(points)
        if (pts.size < 3) return pts
        val out = ArrayList<Pt>()
        out.add(pts.first())
        fitArcs(pts, 0, pts.size - 1, errorTol, samplesPerArc, out)
        return out
    }

    /** Append the arc(s) for pts[[first]..[last]] to [out], which already ends with pts[first]. */
    private fun fitArcs(pts: List<Pt>, first: Int, last: Int, tol: Double, samples: Int, out: MutableList<Pt>) {
        if (isStraight(pts, first, last)) {
            out.add(pts[last])
            return
        }
        val conic = if (last - first + 1 >= MIN_SEG_PTS) fitConic(pts, first, last) else null
        val arc = conic?.let { sampleArc(it, pts, first, last, samples) }
        if (arc == null) {
            if (last - first <= MIN_SEG_PTS) {
                out.add(pts[last]) // too short to fit; join straight
            } else {
                val mid = (first + last) / 2
                fitArcs(pts, first, mid, tol, samples, out)
                fitArcs(pts, mid, last, tol, samples, out)
            }
            return
        }
        val (maxDev, split) = maxDeviation(pts, first, last, arc)
        if (maxDev <= tol || last - first <= MIN_SEG_PTS) {
            for (i in 1 until arc.size) out.add(arc[i]) // arc[0] == out's tail (pts[first])
        } else {
            fitArcs(pts, first, split, tol, samples, out)
            fitArcs(pts, split, last, tol, samples, out)
        }
    }

    // Straight relative to its OWN chord, so a short sub-segment of a curve is not mistaken for a
    // line and flattened to a chord (which made a snapped curve look like joined straight pieces).
    private fun isStraight(pts: List<Pt>, first: Int, last: Int): Boolean {
        val a = pts[first]
        val b = pts[last]
        val chord = a.distanceTo(b)
        if (chord < 1e-6) return false
        val tol = STRAIGHT_REL * chord
        for (i in (first + 1) until last) {
            if (Geometry.distancePointToSegment(pts[i], a, b) > tol) return false
        }
        return true
    }

    /** Direct least-squares ellipse fit over pts[[first]..[last]]; null when no ellipse fits. */
    private fun fitConic(pts: List<Pt>, first: Int, last: Int): Conic? {
        val n = last - first + 1
        var mx = 0.0
        var my = 0.0
        for (i in first..last) {
            mx += pts[i].x
            my += pts[i].y
        }
        mx /= n
        my /= n
        // Scatter blocks for the quadratic (q) and linear (l) parts, in centred coords.
        val s1 = DoubleArray(9)
        val s2 = DoubleArray(9)
        val s3 = DoubleArray(9)
        for (i in first..last) {
            val x = pts[i].x - mx
            val y = pts[i].y - my
            val q = doubleArrayOf(x * x, x * y, y * y)
            val l = doubleArrayOf(x, y, 1.0)
            for (r in 0..2) for (c in 0..2) {
                s1[r * 3 + c] += q[r] * q[c]
                s2[r * 3 + c] += q[r] * l[c]
                s3[r * 3 + c] += l[r] * l[c]
            }
        }
        val s3inv = inv3(s3) ?: return null
        val t = matmul3(s3inv, transpose3(s2))
        for (i in 0..8) t[i] = -t[i]
        val m = DoubleArray(9)
        val st = matmul3(s2, t)
        for (i in 0..8) m[i] = s1[i] + st[i]
        // Premultiply by inv(C1) (the ellipse constraint), which just shuffles M's rows.
        val m2 = DoubleArray(9)
        for (c in 0..2) {
            m2[c] = 0.5 * m[6 + c]
            m2[3 + c] = -m[3 + c]
            m2[6 + c] = 0.5 * m[c]
        }
        val roots = cubicRealRoots(
            -(m2[0] + m2[4] + m2[8]),
            (m2[0] * m2[4] - m2[1] * m2[3]) + (m2[0] * m2[8] - m2[2] * m2[6]) + (m2[4] * m2[8] - m2[5] * m2[7]),
            -det3(m2),
        )
        var best: DoubleArray? = null
        var bestCon = 0.0
        for (lam in roots) {
            val v = nullVector(m2, lam)
            val con = 4.0 * v[0] * v[2] - v[1] * v[1] // ellipse-specific constraint > 0
            if (con > bestCon) {
                bestCon = con
                best = v
            }
        }
        val v = best ?: return null
        val d = t[0] * v[0] + t[1] * v[1] + t[2] * v[2]
        val e = t[3] * v[0] + t[4] * v[1] + t[5] * v[2]
        val f = t[6] * v[0] + t[7] * v[1] + t[8] * v[2]
        val (a, b, c) = v
        // Undo the centring: substitute (x - mx, y - my) back into the centred conic.
        return Conic(
            a, b, c,
            -2.0 * a * mx - b * my + d,
            -b * mx - 2.0 * c * my + e,
            a * mx * mx + b * mx * my + c * my * my - d * mx - e * my + f,
        )
    }

    /**
     * Sample the elliptical arc that the [conic] traces across pts[[first]..[last]]. The ellipse
     * is ray-cast from its centre at each angle the stroke sweeps through (endpoints pinned to the
     * stroke so consecutive arcs meet). Null when the ray-cast degenerates.
     */
    private fun sampleArc(conic: Conic, pts: List<Pt>, first: Int, last: Int, n: Int): List<Pt>? {
        val center = conic.center() ?: return null
        val cx = center.x
        val cy = center.y
        val g = conic.eval(cx, cy)
        // Unwrap the stroke's angle about the centre so the sampled sweep follows the drawn winding.
        var prev = atan2(pts[first].y - cy, pts[first].x - cx)
        val start = prev
        for (i in (first + 1)..last) {
            var ai = atan2(pts[i].y - cy, pts[i].x - cx)
            while (ai - prev > PI) ai -= 2.0 * PI
            while (ai - prev < -PI) ai += 2.0 * PI
            prev = ai
        }
        val end = prev
        val out = ArrayList<Pt>(n + 1)
        for (s in 0..n) {
            when (s) {
                0 -> out.add(pts[first])
                n -> out.add(pts[last])
                else -> {
                    val phi = start + (end - start) * s / n
                    val cph = cos(phi)
                    val sph = sin(phi)
                    val qf = conic.a * cph * cph + conic.b * cph * sph + conic.c * sph * sph
                    val rr = -g / qf
                    if (qf <= 0.0 || rr <= 0.0) return null
                    val r = sqrt(rr)
                    out.add(Pt(cx + r * cph, cy + r * sph))
                }
            }
        }
        return out
    }

    private fun maxDeviation(pts: List<Pt>, first: Int, last: Int, arc: List<Pt>): Pair<Double, Int> {
        var maxDev = 0.0
        var split = (first + last) / 2
        for (i in (first + 1) until last) {
            var d = Double.MAX_VALUE
            for (j in 0 until arc.size - 1) {
                val seg = Geometry.distancePointToSegment(pts[i], arc[j], arc[j + 1])
                if (seg < d) d = seg
            }
            if (d >= maxDev) {
                maxDev = d
                split = i
            }
        }
        return maxDev to split
    }

    private fun dedupe(points: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>(points.size)
        for (p in points) if (out.isEmpty() || out.last().distanceTo(p) > 1e-9) out.add(p)
        return out
    }

    // --- small linear-algebra helpers (3x3, row-major) ---

    private fun det3(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun inv3(m: DoubleArray): DoubleArray? {
        val det = det3(m)
        if (abs(det) < 1e-15) return null
        val inv = DoubleArray(9)
        inv[0] = (m[4] * m[8] - m[5] * m[7]) / det
        inv[1] = (m[2] * m[7] - m[1] * m[8]) / det
        inv[2] = (m[1] * m[5] - m[2] * m[4]) / det
        inv[3] = (m[5] * m[6] - m[3] * m[8]) / det
        inv[4] = (m[0] * m[8] - m[2] * m[6]) / det
        inv[5] = (m[2] * m[3] - m[0] * m[5]) / det
        inv[6] = (m[3] * m[7] - m[4] * m[6]) / det
        inv[7] = (m[1] * m[6] - m[0] * m[7]) / det
        inv[8] = (m[0] * m[4] - m[1] * m[3]) / det
        return inv
    }

    private fun matmul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return o
    }

    private fun transpose3(m: DoubleArray): DoubleArray =
        doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    /** A vector in the null space of (m - λI): the longest cross product of two of its rows. */
    private fun nullVector(m: DoubleArray, lambda: Double): DoubleArray {
        val r0 = doubleArrayOf(m[0] - lambda, m[1], m[2])
        val r1 = doubleArrayOf(m[3], m[4] - lambda, m[5])
        val r2 = doubleArrayOf(m[6], m[7], m[8] - lambda)
        val c01 = cross(r0, r1)
        val c02 = cross(r0, r2)
        val c12 = cross(r1, r2)
        val n01 = dot(c01, c01)
        val n02 = dot(c02, c02)
        val n12 = dot(c12, c12)
        return if (n01 >= n02 && n01 >= n12) c01 else if (n02 >= n12) c02 else c12
    }

    private fun cross(a: DoubleArray, b: DoubleArray): DoubleArray =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Real roots of x³ + p x² + q x + r = 0. */
    private fun cubicRealRoots(p: Double, q: Double, r: Double): DoubleArray {
        val shift = -p / 3.0
        val pp = q - p * p / 3.0
        val qq = 2.0 * p * p * p / 27.0 - p * q / 3.0 + r
        val disc = qq * qq / 4.0 + pp * pp * pp / 27.0
        return when {
            disc > 1e-12 -> {
                val s = sqrt(disc)
                doubleArrayOf(cbrt(-qq / 2.0 + s) + cbrt(-qq / 2.0 - s) + shift)
            }
            disc < -1e-12 -> {
                val m = 2.0 * sqrt(-pp / 3.0)
                val theta = acos(((3.0 * qq) / (2.0 * pp) * sqrt(-3.0 / pp)).coerceIn(-1.0, 1.0)) / 3.0
                doubleArrayOf(
                    m * cos(theta) + shift,
                    m * cos(theta - 2.0 * PI / 3.0) + shift,
                    m * cos(theta - 4.0 * PI / 3.0) + shift,
                )
            }
            else -> {
                val u = cbrt(-qq / 2.0)
                doubleArrayOf(2.0 * u + shift, -u + shift)
            }
        }
    }

    private fun cbrt(x: Double): Double = if (x < 0) -((-x).pow(1.0 / 3.0)) else x.pow(1.0 / 3.0)
}
