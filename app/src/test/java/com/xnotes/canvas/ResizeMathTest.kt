package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeMathTest {

    @Test fun imageResizeIsAspectLocked() {
        val r = ResizeMath.resizeImage(Rect(0.0, 0.0, 100.0, 50.0), HandleId.BR, Pt(200.0, 60.0))
        // scale = max(200/100, 60/50) = 2 -> 200x100, anchored at TL
        assertEquals(Rect(0.0, 0.0, 200.0, 100.0), r)
    }

    @Test fun imageResizeAnchorsOppositeCorner() {
        val r = ResizeMath.resizeImage(Rect(10.0, 10.0, 100.0, 50.0), HandleId.TL, Pt(-90.0, -40.0))
        // anchor = BR (110,60); rawW=200,rawH=100 -> scale 2 -> 200x100 from BR
        assertEquals(110.0, r.right, 1e-9)
        assertEquals(60.0, r.bottom, 1e-9)
        assertEquals(200.0, r.w, 1e-9)
    }

    @Test fun imageResizeEnforcesMinSize() {
        val r = ResizeMath.resizeImage(Rect(0.0, 0.0, 100.0, 100.0), HandleId.BR, Pt(5.0, 5.0))
        assertTrue(r.w >= ResizeMath.MIN_SIZE && r.h >= ResizeMath.MIN_SIZE)
    }

    @Test fun closedShapeResizesFreely() {
        val (s, e) = ResizeMath.resizeClosedShape(Pt(0.0, 0.0), Pt(100.0, 50.0), HandleId.BR, Pt(200.0, 60.0))
        assertEquals(Pt(0.0, 0.0), s)
        assertEquals(Pt(200.0, 60.0), e) // not aspect-locked
    }

    @Test fun openShapeDragsGrabbedEndpoint() {
        val (s, e) = ResizeMath.resizeOpenShape(Pt(0.0, 0.0), Pt(100.0, 0.0), HandleId.END, Pt(50.0, 50.0))
        assertEquals(Pt(0.0, 0.0), s)
        assertEquals(Pt(50.0, 50.0), e)
    }

    @Test fun textResizeWidthOnly() {
        val (pos1, w1) = ResizeMath.resizeText(Pt(0.0, 0.0), 100.0, HandleId.R, 150.0)
        assertEquals(Pt(0.0, 0.0), pos1)
        assertEquals(150.0, w1, 1e-9)

        val (pos2, w2) = ResizeMath.resizeText(Pt(0.0, 0.0), 100.0, HandleId.L, 20.0)
        assertEquals(20.0, pos2.x, 1e-9) // left moved, right edge (100) fixed
        assertEquals(80.0, w2, 1e-9)
    }

    @Test fun imageHandlesAreFourCorners() {
        val img = ImageItem(FakeRasterSurface(10, 10), Rect(0.0, 0.0, 100.0, 50.0))
        val handles = ResizeMath.handles(img, Pt(48.0, 48.0))
        assertEquals(4, handles.size)
        assertTrue(handles.any { it.id == HandleId.TL && it.content == Pt(48.0, 48.0) })
        assertTrue(handles.any { it.id == HandleId.BR && it.content == Pt(148.0, 98.0) })
    }
}
