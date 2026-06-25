package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt

/**
 * One captured stylus point, page-local; `pressure` in `[0, 1]`. [t] is the
 * milliseconds elapsed since the stroke's first sample (0 for that first one),
 * used only by velocity-aware tools (the speed pen); 0 everywhere else.
 */
data class Sample(val x: Double, val y: Double, val pressure: Double, val t: Double = 0.0) {
    val pos: Pt get() = Pt(x, y)
}

/** A round dot for a single-sample tap, or the pen's and highlighter's round head/tail end-caps;
 *  every other multi-sample ribbon ends flat and emits no caps. */
data class Cap(val center: Pt, val radius: Double)

/**
 * The geometry derived from a stroke's samples (spec 03). A committed stroke fills the ribbon as
 * the additive triangle [mesh] (hole-free); the live in-progress stroke fills the single [outline]
 * (nonzero winding) for speed. Both add the [caps] discs, in the ink colour, with no outline pen.
 */
data class StrokeGeometry(
    val outline: List<Pt>,
    val caps: List<Cap>,
    val centerline: List<Pt>,
    val halfWidths: List<Double>,
) {
    /**
     * A concentric inner ribbon scaled to [frac] of this ribbon's half-widths about
     * the same centerline (1.0 reproduces [outline]). The neon white-hot core fills
     * this rather than the full outline, so the outer `1 − frac` of the tube keeps
     * its saturated ink colour. Empty when there's no ribbon (a single-sample dot)
     * or [frac] ≤ 0. [outline] is `left[0..n-1] ++ right[n-1..0]` about [centerline],
     * so each edge point is moved a fraction of the way back toward its centre.
     */
    fun coreOutline(frac: Double): List<Pt> {
        val n = centerline.size
        if (frac <= 0.0 || n < 2 || outline.size != 2 * n) return emptyList()
        val f = frac.coerceAtMost(1.0)
        val core = ArrayList<Pt>(2 * n)
        for (i in 0 until n) core.add(centerline[i] + (outline[i] - centerline[i]) * f)
        for (i in n - 1 downTo 0) core.add(centerline[i] + (outline[2 * n - 1 - i] - centerline[i]) * f)
        return core
    }

    /** The ribbon as one additive triangle mesh (flat triples). Every triangle is wound the same
     *  way, so a single nonzero fill is their UNION: where a sharp turn folds the ribbon over
     *  itself the overlap reinforces to solid ink instead of cancelling to a gap, the way the one
     *  signed [outline] does. Committed strokes fill this; the live stroke keeps the cheaper
     *  [outline]. Built once and reused. Empty for a single-sample dot. */
    val mesh: List<Pt> by lazy { buildMesh() }

    private fun buildMesh(): List<Pt> {
        val n = centerline.size
        if (n < 2 || outline.size != 2 * n) return emptyList()
        val tris = ArrayList<Pt>(6 * (n - 1))
        var lPrev = outline[0]
        var rPrev = outline[2 * n - 1]
        for (i in 1 until n) {
            val lCur = outline[i]
            val rCur = outline[2 * n - 1 - i]
            addTri(tris, lPrev, rPrev, rCur)
            addTri(tris, lPrev, rCur, lCur)
            lPrev = lCur
            rPrev = rCur
        }
        return tris
    }

    // Append one triangle with a consistent (positive) winding, so the nonzero fill unions the
    // mesh instead of letting an opposite-wound overlap subtract a gap back out.
    private fun addTri(out: MutableList<Pt>, a: Pt, b: Pt, c: Pt) {
        val area2 = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        out.add(a)
        if (area2 < 0.0) { out.add(c); out.add(b) } else { out.add(b); out.add(c) }
    }

    companion object {
        val EMPTY = StrokeGeometry(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
