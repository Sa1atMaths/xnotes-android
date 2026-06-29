package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class ItemTransformTest {

    private fun horizontalStroke(width: Double = 4.0): Stroke {
        val s = Stroke(Tool.PEN, ToolConfig(baseWidth = width))
        s.addSample(Sample(0.0, 0.0, 1.0))
        s.addSample(Sample(10.0, 0.0, 1.0))
        s.addSample(Sample(20.0, 0.0, 1.0))
        return s
    }

    // --- strokes ---

    @Test fun scaleStrokeMovesSamplesAndScalesWidth() {
        val s = horizontalStroke(4.0)
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(Pt(20.0, 0.0), s.centroid()) // samples doubled about the origin
        assertEquals(8.0, s.config.baseWidth, 1e-9) // width * sqrt(det)=*2
    }

    @Test fun rotateStrokeKeepsWidth() {
        val s = horizontalStroke(4.0)
        s.applyTransform(Affine.rotateAbout(Pt.ZERO, PI / 2))
        assertEquals(0.0, s.centroid().x, 1e-9)
        assertEquals(10.0, s.centroid().y, 1e-9) // horizontal run becomes vertical
        assertEquals(4.0, s.config.baseWidth, 1e-9) // rotation leaves width alone
    }

    @Test fun strokeSnapshotRestoreRoundTrips() {
        val s = horizontalStroke(4.0)
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 3.0, 3.0))
        s.restoreGeometry(snap)
        assertEquals(Pt(10.0, 0.0), s.centroid())
        assertEquals(4.0, s.config.baseWidth, 1e-9)
    }

    // --- shapes ---

    @Test fun scaleRectangleStaysParametric() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 3.0)
        s.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertEquals(Pt(0.0, 0.0), s.start)
        assertEquals(Pt(200.0, 100.0), s.end)
        assertEquals(6.0, s.strokeWidth, 1e-9)
        assertNull(s.points)
    }

    @Test fun rotateRectangleBecomesPolygon() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 3.0)
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 2)) // about the box centre
        assertEquals(ShapeKind.POLYGON, s.shape)
        assertNotNull(s.points)
        assertEquals(4, s.points!!.size)
        assertEquals(3.0, s.strokeWidth, 1e-9) // rotation keeps the width
        // A 100x50 rect turned 90 degrees about its centre bounds to 50x100 at (25,-25).
        assertEquals(25.0, s.start.x, 1e-9)
        assertEquals(-25.0, s.start.y, 1e-9)
        assertEquals(75.0, s.end.x, 1e-9)
        assertEquals(75.0, s.end.y, 1e-9)
    }

    @Test fun rotateLineKeepsKind() {
        val s = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(10.0, 0.0), Rgba(0, 0, 0), 3.0)
        s.applyTransform(Affine.rotateAbout(Pt.ZERO, PI / 2))
        assertEquals(ShapeKind.LINE, s.shape)
        assertEquals(0.0, s.end.x, 1e-9)
        assertEquals(10.0, s.end.y, 1e-9)
    }

    @Test fun shapeSnapshotRestoresKindAfterRotation() {
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 3.0)
        val snap = s.snapshotGeometry()
        s.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), PI / 4))
        assertEquals(ShapeKind.POLYGON, s.shape)
        s.restoreGeometry(snap)
        assertEquals(ShapeKind.RECTANGLE, s.shape)
        assertNull(s.points)
        assertEquals(Pt(100.0, 50.0), s.end)
    }

    // --- images ---

    @Test fun scaleImageRect() {
        val img = ImageItem(ImageData(ByteArray(0), 10, 10), Rect(10.0, 10.0, 100.0, 50.0))
        img.applyTransform(Affine.scaleAbout(Pt(10.0, 10.0), 2.0, 2.0))
        assertEquals(Rect(10.0, 10.0, 200.0, 100.0), img.rect)
    }

    // --- text ---

    @Test fun cornerScaleGrowsFontAndBox() {
        val t = TextItem(Pt(0.0, 0.0), width = 100.0, height = 40.0, text = "x", pointSize = 10.0, measurer = FakeTextMeasurer())
        t.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        assertEquals(200.0, t.width, 1e-9)
        assertEquals(20.0, t.pointSize, 1e-9)
        assertEquals(80.0, t.height, 1e-9) // max(40, content) * 2
    }

    @Test fun edgeScaleLeavesFontAlone() {
        val t = TextItem(Pt(0.0, 0.0), width = 100.0, height = 40.0, text = "x", pointSize = 10.0, measurer = FakeTextMeasurer())
        t.applyTransform(Affine.scaleAbout(Pt.ZERO, 2.0, 1.0)) // a horizontal edge drag
        assertEquals(200.0, t.width, 1e-9)
        assertEquals(10.0, t.pointSize, 1e-9) // font unchanged
        assertEquals(40.0, t.height, 1e-9) // vertical axis untouched
    }
}
