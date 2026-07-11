package com.xnotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfColorFilterTest {

    /** Apply a 4x5 ColorMatrix-layout matrix to an rgb triple (alpha assumed 255, no clamping). */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun row(i: Int) = m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 3] * 255f + m[i * 5 + 4]
        return Triple(row(0), row(1), row(2))
    }

    @Test fun identityLeavesPixelsAlone() {
        assertTrue(PdfColorFilter.isIdentity(100, 0, 100, 0))
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 0, 100, 0), 12f, 200f, 99f)
        assertEquals(12f, r, 1e-4f)
        assertEquals(200f, g, 1e-4f)
        assertEquals(99f, b, 1e-4f)
    }

    @Test fun fullInvertFlipsChannels() {
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 100, 100, 0), 255f, 0f, 60f)
        assertEquals(0f, r, 1e-3f)
        assertEquals(255f, g, 1e-3f)
        assertEquals(195f, b, 1e-3f)
    }

    @Test fun halfInvertMeetsInTheMiddle() {
        // CSS invert(50%) maps every channel to 127.5.
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 50, 100, 0), 255f, 0f, 40f)
        assertEquals(127.5f, r, 1e-3f)
        assertEquals(127.5f, g, 1e-3f)
        assertEquals(127.5f, b, 1e-3f)
    }

    @Test fun brightnessScalesLinearly() {
        val (r, _, _) = apply(PdfColorFilter.matrix(100, 0, 50, 0), 200f, 200f, 200f)
        assertEquals(100f, r, 1e-3f)
    }

    @Test fun contrastPivotsOnMidGrey() {
        val m = PdfColorFilter.matrix(200, 0, 100, 0)
        val (mid, _, _) = apply(m, 127.5f, 127.5f, 127.5f)
        assertEquals(127.5f, mid, 1e-3f) // mid-grey is the fixed point
        val (r, _, _) = apply(m, 100f, 100f, 100f)
        assertEquals(72.5f, r, 1e-3f) // (100 - 127.5) * 2 + 127.5
    }

    @Test fun fullSepiaMatchesTheSpecMatrix() {
        val (r, g, b) = apply(PdfColorFilter.matrix(100, 0, 100, 100), 100f, 100f, 100f)
        assertEquals(135.1f, r, 0.1f)
        assertEquals(120.3f, g, 0.1f)
        assertEquals(93.7f, b, 0.1f)
    }

    @Test fun contrastAppliesBeforeInvert() {
        // contrast(200%) turns 100 into 72.5; invert(100%) then yields 182.5.
        val (r, _, _) = apply(PdfColorFilter.matrix(200, 100, 100, 0), 100f, 100f, 100f)
        assertEquals(182.5f, r, 1e-3f)
    }

    @Test fun pageFilterIsNoneAtDefaults() {
        assertSame(PdfPageFilter.NONE, PdfPageFilter.of(100, 0, 100, 0, keepImages = true))
        assertNull(PdfPageFilter.NONE.pageMatrix)
        assertFalse(PdfPageFilter.NONE.stampImages)
    }

    @Test fun imagesStampRawUnderAnyFilter() {
        // "Don't filter images" exempts them from the whole chain, not just the invert.
        val inverted = PdfPageFilter.of(100, 100, 100, 0, keepImages = true)
        assertNotNull(inverted.pageMatrix)
        assertTrue(inverted.stampImages)
        val sepiaOnly = PdfPageFilter.of(100, 0, 100, 30, keepImages = true)
        assertNotNull(sepiaOnly.pageMatrix)
        assertTrue(sepiaOnly.stampImages)
    }

    @Test fun noStampingWhenImagesFollowTheFilter() {
        val f = PdfPageFilter.of(100, 100, 100, 0, keepImages = false)
        assertNotNull(f.pageMatrix)
        assertFalse(f.stampImages)
    }
}
