package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Spec-style checks for the cubic Bézier fitter. */
class CurveFitTest {

    @Test fun fitsHalfCircleCloseToTheArc() {
        val arc = (0..48).map { i ->
            val a = PI * i / 48.0
            Pt(200.0 + 150.0 * cos(a), 200.0 + 150.0 * sin(a))
        }
        val sampled = CurveFit.fitSampled(arc, 6.0, 16)
        assertTrue("the spline yields a usable polyline", sampled.size >= 8)
        // Every sampled point stays near the true circle of radius 150 about (200, 200).
        for (p in sampled) assertEquals(150.0, hypot(p.x - 200.0, p.y - 200.0), 10.0)
        // Endpoints are preserved.
        assertEquals(arc.first().x, sampled.first().x, 1.0)
        assertEquals(arc.last().y, sampled.last().y, 1.0)
    }

    @Test fun fitsStraightRunOnTheLine() {
        val line = (0..20).map { Pt(it * 10.0, it * 10.0) }
        val sampled = CurveFit.fitSampled(line, 4.0, 12)
        for (p in sampled) assertEquals(p.x, p.y, 2.0) // stays on y = x
    }

    @Test fun toleranceControlsSegmentCount() {
        val arc = (0..48).map { i ->
            val a = PI * i / 48.0
            Pt(150.0 * cos(a), 150.0 * sin(a))
        }
        // A looser tolerance never needs more segments than a tighter one.
        val loose = CurveFit.fit(arc, 30.0).size
        val tight = CurveFit.fit(arc, 2.0).size
        assertTrue(loose <= tight)
    }
}
