package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Spec-style checks for the interpolating-spline curve fitter. */
class SplineFitTest {

    private fun nearestDist(p: Pt, curve: List<Pt>): Double {
        var d = Double.MAX_VALUE
        for (j in 0 until curve.size - 1) {
            val s = Geometry.distancePointToSegment(p, curve[j], curve[j + 1])
            if (s < d) d = s
        }
        return d
    }

    @Test fun followsAWideCArc() {
        // A 200° arc (wider than any single cubic can bend): the spline must still hug it.
        val cx = 400.0
        val cy = 400.0
        val r = 250.0
        val arc = (0..60).map { i ->
            val a = -PI * 0.55 + (1.45 * PI) * i / 60
            Pt(cx + r * cos(a), cy + r * sin(a))
        }
        val curve = SplineFit.fitSampled(arc, 7, 16, 0.06 * (2 * r))
        assertNotNull(curve)
        for (p in arc) assertTrue("strayed from the arc", nearestDist(p, curve!!) <= 12.0)
        assertEquals(arc.first().x, curve!!.first().x, 1.0)
        assertEquals(arc.last().y, curve.last().y, 1.0)
    }

    @Test fun followsAnS() {
        val s = (0..60).map { i ->
            val t = i / 60.0
            Pt(200.0 + 90.0 * sin(2.0 * PI * t), 40.0 + 320.0 * t)
        }
        val curve = SplineFit.fitSampled(s, 7, 16, 0.06 * hypot(180.0, 320.0))
        assertNotNull(curve)
        for (p in s) assertTrue("strayed from the S", nearestDist(p, curve!!) <= 18.0)
    }

    @Test fun rejectsAComplexSquiggle() {
        // Three sine periods: a few anchors can't capture it, so it must be left as ink (null).
        val sq = (0..80).map { i ->
            val t = i / 80.0
            Pt(100.0 + 360.0 * t, 300.0 + 110.0 * sin(2.0 * PI * 3.0 * t))
        }
        assertNull(SplineFit.fitSampled(sq, 7, 16, 0.06 * hypot(360.0, 220.0)))
    }
}
