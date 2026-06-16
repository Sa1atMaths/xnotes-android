package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Spec-style checks for the elliptical-arc fitter. */
class EllipseArcFitTest {

    /** Normalized radial value of [p] on the axis-aligned ellipse (cx,cy,rx,ry): 1 on the curve. */
    private fun onEllipse(p: Pt, cx: Double, cy: Double, rx: Double, ry: Double): Double =
        ((p.x - cx) / rx).pow(2) + ((p.y - cy) / ry).pow(2)

    @Test fun fitsAxisAlignedEllipticalArc() {
        // ~150° arc of an ellipse rx=180, ry=90 about (250,200).
        val arc = (0..44).map { i ->
            val a = -PI / 2 + (1.4 * PI) * i / 44
            Pt(250.0 + 180.0 * cos(a), 200.0 + 90.0 * sin(a))
        }
        val sampled = EllipseArcFit.fitSampled(arc, 6.0, 20)
        assertTrue(sampled.size >= 8)
        for (p in sampled) assertEquals(1.0, onEllipse(p, 250.0, 200.0, 180.0, 90.0), 0.06)
        assertEquals(arc.first().x, sampled.first().x, 1.0)
        assertEquals(arc.last().y, sampled.last().y, 1.0)
    }

    @Test fun fitsTiltedEllipticalArc() {
        // The same ellipse rotated 30°, so the fit must recover a rotated conic.
        val th = PI / 6
        val arc = (0..44).map { i ->
            val a = -PI / 2 + (1.4 * PI) * i / 44
            val ex = 180.0 * cos(a)
            val ey = 90.0 * sin(a)
            Pt(250.0 + ex * cos(th) - ey * sin(th), 200.0 + ex * sin(th) + ey * cos(th))
        }
        val sampled = EllipseArcFit.fitSampled(arc, 6.0, 20)
        // Un-rotate each sample and check it lands on the axis-aligned ellipse.
        for (p in sampled) {
            val dx = p.x - 250.0
            val dy = p.y - 200.0
            val ux = dx * cos(th) + dy * sin(th)
            val uy = -dx * sin(th) + dy * cos(th)
            assertEquals(1.0, (ux / 180.0).pow(2) + (uy / 90.0).pow(2), 0.08)
        }
    }

    @Test fun fitsLargeArcAtPageScale() {
        // A big C high in page coordinates: the direct fit must stay conditioned and trace the arc,
        // not collapse to straight chords. Radius 600 sampled points must stay near radius 600.
        val cx = 1500.0
        val cy = 1200.0
        val r = 600.0
        val arc = (0..50).map { i ->
            val a = -PI * 0.7 + (1.4 * PI) * i / 50
            Pt(cx + r * cos(a), cy + r * sin(a))
        }
        val sampled = EllipseArcFit.fitSampled(arc, 18.0, 20)
        for (p in sampled) assertEquals(r, hypot(p.x - cx, p.y - cy), 14.0)
    }

    @Test fun nonEllipticalCurveStaysDenseNotChords() {
        // A parabola arc (not a true ellipse): the fitter must approximate it with smooth arcs,
        // never collapse short sub-segments to straight chords (the "joined straight lines" bug).
        val pts = (0..60).map { i ->
            val x = -150.0 + 300.0 * i / 60
            Pt(400.0 + x, 300.0 + x * x / 300.0)
        }
        val sampled = EllipseArcFit.fitSampled(pts, 0.05 * hypot(300.0, 75.0), 18)
        assertTrue("expected a dense smooth curve, got ${sampled.size} points", sampled.size >= 14)
        for (p in pts) {
            var nearest = Double.MAX_VALUE
            for (j in 0 until sampled.size - 1) {
                val d = Geometry.distancePointToSegment(p, sampled[j], sampled[j + 1])
                if (d < nearest) nearest = d
            }
            assertTrue("point strayed from the fit: $nearest", nearest <= 8.0)
        }
    }

    @Test fun sCurveFollowsTheStroke() {
        // A descending S: the fitter must split into arcs that still hug the stroke.
        val s = (0..60).map { i ->
            val t = i / 60.0
            Pt(200.0 + 90.0 * sin(2.0 * PI * t), 40.0 + 320.0 * t)
        }
        val sampled = EllipseArcFit.fitSampled(s, 8.0, 18)
        // Every original point stays close to the fitted polyline.
        for (p in s) {
            var nearest = Double.MAX_VALUE
            for (j in 0 until sampled.size - 1) {
                val d = Geometry.distancePointToSegment(p, sampled[j], sampled[j + 1])
                if (d < nearest) nearest = d
            }
            assertTrue("point strayed from the fit: $nearest", nearest <= 16.0)
        }
    }
}
