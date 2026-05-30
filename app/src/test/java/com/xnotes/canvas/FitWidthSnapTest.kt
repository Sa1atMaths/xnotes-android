package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the live magnetic fit-to-width snap and the sidebar-resize re-fit. */
class FitWidthSnapTest {

    private fun state(pages: Int = 1, viewportW: Int = 1000): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.forAppearance(true, Rgba(0, 230, 118))).apply {
            this.viewportW = viewportW
            viewportH = 1400
            relayout()
        }

    @Test fun zoomWithinBandSticksToFitWidth() {
        val st = state()
        val fit = st.fitWidthZoom()
        assertFalse(st.fitWidthActive)

        val z = st.snapZoomToFitWidth(fit * 1.03) // inside the ±5% band
        assertEquals(fit, z, 1e-9) // stuck exactly to fit-width
        assertTrue(st.fitWidthActive)
    }

    @Test fun zoomOutsideBandPassesThroughAndClearsFit() {
        val st = state()
        val fit = st.fitWidthZoom()
        st.fitWidthActive = true // pretend we were stuck; leaving the band must release

        val z = st.snapZoomToFitWidth(fit * 1.3)
        assertEquals(fit * 1.3, z, 1e-9) // raw zoom passes through untouched
        assertFalse(st.fitWidthActive)
    }

    @Test fun resizeRefitsWidthWhileFitActive() {
        val st = state(viewportW = 1000)
        st.fitWidth()
        val wide = st.zoom
        assertTrue(st.fitWidthActive)

        // Sidebar opens: narrower viewport, same content width.
        st.viewportW = 776
        st.relayout()
        st.reflowFitWidthForResize()

        assertEquals(st.fitWidthZoom(), st.zoom, 1e-9)
        assertTrue(st.zoom < wide)
    }

    @Test fun resizeRefitsEvenWhenZoomLocked() {
        val st = state(viewportW = 1000)
        st.fitWidth()
        st.zoomLocked = true // lock freezes user gestures, not the resize re-fit

        st.viewportW = 776
        st.relayout()
        st.reflowFitWidthForResize()

        assertEquals(st.fitWidthZoom(), st.zoom, 1e-9)
    }

    @Test fun resizeIsNoOpWhenNotFitToWidth() {
        val st = state(viewportW = 1000)
        st.zoom = 2.5
        st.fitWidthActive = false

        st.viewportW = 776
        st.relayout()
        st.reflowFitWidthForResize()

        assertEquals(2.5, st.zoom, 1e-9) // arbitrary zoom preserved
    }
}
