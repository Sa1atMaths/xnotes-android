package com.xnotes.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class AffineTest {

    @Test fun scaleAboutKeepsPivotFixed() {
        val t = Affine.scaleAbout(Pt(10.0, 10.0), 2.0, 3.0)
        assertEquals(Pt(10.0, 10.0), t.apply(Pt(10.0, 10.0)))
        assertEquals(Pt(30.0, 10.0), t.apply(Pt(20.0, 10.0)))
        assertEquals(Pt(10.0, 40.0), t.apply(Pt(10.0, 20.0)))
    }

    @Test fun scaleProperties() {
        val t = Affine.scaleAbout(Pt.ZERO, 2.0, 3.0)
        assertEquals(2.0, t.scaleX, 1e-9)
        assertEquals(3.0, t.scaleY, 1e-9)
        assertEquals(6.0, t.determinant, 1e-9)
        assertEquals(Math.sqrt(6.0), t.linearScale, 1e-9)
        assertTrue(t.isAxisAligned)
        assertFalse(t.isUniformScale)
        assertTrue(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0).isUniformScale)
    }

    @Test fun rotateAboutKeepsCenterFixedAndTurnsClockwise() {
        val c = Pt(5.0, 7.0)
        val t = Affine.rotateAbout(c, PI / 2)
        val fixed = t.apply(c)
        assertEquals(c.x, fixed.x, 1e-9)
        assertEquals(c.y, fixed.y, 1e-9)
        // +x maps to +y (downward = clockwise in screen space) about the origin.
        val r = Affine.rotateAbout(Pt.ZERO, PI / 2).apply(Pt(1.0, 0.0))
        assertEquals(0.0, r.x, 1e-9)
        assertEquals(1.0, r.y, 1e-9)
    }

    @Test fun rotationHasUnitScale() {
        val t = Affine.rotateAbout(Pt(3.0, 4.0), 0.7)
        assertEquals(1.0, t.scaleX, 1e-9)
        assertEquals(1.0, t.scaleY, 1e-9)
        assertEquals(1.0, t.linearScale, 1e-9)
        assertFalse(t.isAxisAligned)
    }
}
