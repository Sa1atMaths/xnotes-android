package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic, seeded handwriting vectors exercising [ShapeRecognizer]. */
class ShapeRecognizerTest {

    private val rnd = Random(42)

    private fun jit(amp: Double) = (rnd.nextDouble() * 2.0 - 1.0) * amp

    private fun circle(cx: Double, cy: Double, r: Double, n: Int, noise: Double): List<Pt> =
        (0 until n).map { i ->
            val a = 2.0 * PI * i / n
            Pt(cx + r * cos(a) + jit(noise), cy + r * sin(a) + jit(noise))
        }

    private fun line(a: Pt, b: Pt, n: Int, noise: Double): List<Pt> =
        (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            Pt(a.x + t * (b.x - a.x) + jit(noise), a.y + t * (b.y - a.y) + jit(noise))
        }

    /** Walk the polygon edges (incl. the closing edge), so first ≈ last ⇒ a closed path. */
    private fun polygon(vertices: List<Pt>, perEdge: Int, noise: Double): List<Pt> {
        val out = ArrayList<Pt>()
        for (e in vertices.indices) {
            val a = vertices[e]
            val b = vertices[(e + 1) % vertices.size]
            for (s in 0 until perEdge) {
                val t = s.toDouble() / perEdge
                out.add(Pt(a.x + t * (b.x - a.x) + jit(noise), a.y + t * (b.y - a.y) + jit(noise)))
            }
        }
        return out
    }

    // --- positives ---

    @Test fun circleSnapsToEllipse() {
        val rec = ShapeRecognizer.recognizePoints(circle(200.0, 200.0, 150.0, 48, 5.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.ELLIPSE, rec!!.kind)
        assertEquals(200.0, (rec.start.x + rec.end.x) / 2.0, 20.0)
        assertEquals(200.0, (rec.start.y + rec.end.y) / 2.0, 20.0)
        assertEquals(150.0, abs(rec.end.x - rec.start.x) / 2.0, 25.0)
        assertEquals(150.0, abs(rec.end.y - rec.start.y) / 2.0, 25.0)
    }

    @Test fun straightStrokeSnapsToLine() {
        val a = Pt(60.0, 70.0)
        val b = Pt(360.0, 300.0)
        val rec = ShapeRecognizer.recognizePoints(line(a, b, 40, 4.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertEquals(a.x, rec.start.x, 12.0)
        assertEquals(a.y, rec.start.y, 12.0)
        assertEquals(b.x, rec.end.x, 12.0)
        assertEquals(b.y, rec.end.y, 12.0)
    }

    @Test fun boxSnapsToRectangle() {
        val sq = polygon(
            listOf(Pt(50.0, 50.0), Pt(250.0, 50.0), Pt(250.0, 250.0), Pt(50.0, 250.0)),
            perEdge = 16, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(sq)
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
        assertEquals(200.0, abs(rec.end.x - rec.start.x), 30.0)
        assertEquals(200.0, abs(rec.end.y - rec.start.y), 30.0)
    }

    @Test fun wideBoxKeepsAspect() {
        val r = polygon(
            listOf(Pt(40.0, 80.0), Pt(340.0, 80.0), Pt(340.0, 230.0), Pt(40.0, 230.0)),
            perEdge = 16, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(r)
        assertNotNull(rec)
        assertEquals(ShapeKind.RECTANGLE, rec!!.kind)
        assertEquals(300.0, abs(rec.end.x - rec.start.x), 35.0)
        assertEquals(150.0, abs(rec.end.y - rec.start.y), 35.0)
    }

    @Test fun threeCornerStrokeSnapsToTriangle() {
        val tri = polygon(
            listOf(Pt(200.0, 40.0), Pt(360.0, 340.0), Pt(40.0, 340.0)),
            perEdge = 22, noise = 5.0,
        )
        val rec = ShapeRecognizer.recognizePoints(tri)
        assertNotNull(rec)
        assertEquals(ShapeKind.TRIANGLE, rec!!.kind)
    }

    // --- negatives (stay ink) ---

    @Test fun tapIsNotAShape() {
        assertNull(ShapeRecognizer.recognizePoints(listOf(Pt(10.0, 10.0), Pt(11.0, 11.0), Pt(10.5, 10.5))))
    }

    @Test fun tinyStrokeIsNotAShape() {
        assertNull(ShapeRecognizer.recognizePoints(circle(10.0, 10.0, 6.0, 40, 0.5)))
    }

    @Test fun openZigZagIsNotAShape() {
        val zig = listOf(
            Pt(0.0, 0.0), Pt(60.0, 80.0), Pt(120.0, 0.0),
            Pt(180.0, 80.0), Pt(240.0, 0.0), Pt(300.0, 80.0),
        )
        assertNull(ShapeRecognizer.recognizePoints(zig))
    }

    @Test fun openArcIsNotAShape() {
        // Half circle: ends sit a diameter apart (open) and it bows far from its chord (not a line).
        val arc = (0..40).map { i ->
            val a = PI * i / 40.0
            Pt(200.0 + 150.0 * cos(a), 200.0 + 150.0 * sin(a))
        }
        assertNull(ShapeRecognizer.recognizePoints(arc))
    }

    // --- properties ---

    @Test fun recognitionIsDeterministic() {
        val pts = circle(150.0, 150.0, 120.0, 48, 4.0)
        assertEquals(ShapeRecognizer.recognizePoints(pts), ShapeRecognizer.recognizePoints(pts))
    }

    @Test fun classificationIsScaleInvariant() {
        val small = circle(100.0, 100.0, 80.0, 48, 3.0)
        val big = small.map { Pt(it.x * 5.0, it.y * 5.0) }
        assertEquals(ShapeKind.ELLIPSE, ShapeRecognizer.recognizePoints(small)?.kind)
        assertEquals(ShapeKind.ELLIPSE, ShapeRecognizer.recognizePoints(big)?.kind)
    }

    @Test fun recognizeReadsSamplePositions() {
        val samples = circle(180.0, 180.0, 140.0, 48, 4.0).map { Sample(it.x, it.y, 1.0) }
        val rec = ShapeRecognizer.recognize(samples)
        assertNotNull(rec)
        assertEquals(ShapeKind.ELLIPSE, rec!!.kind)
    }

    @Test fun lineEndpointsAreOrdered() {
        // A near-horizontal stroke stays open and snaps to a line between its true ends.
        val rec = ShapeRecognizer.recognizePoints(line(Pt(20.0, 100.0), Pt(320.0, 110.0), 30, 3.0))
        assertNotNull(rec)
        assertEquals(ShapeKind.LINE, rec!!.kind)
        assertTrue(rec.start.x < rec.end.x)
    }
}
