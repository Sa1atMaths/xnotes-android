package com.xnotes.core.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conformance vectors from spec 03 §6. `width` below means `2 × half_width`. */
class StrokeEngineTest {

    private fun width(
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        pressure: Double,
        ty: Double,
    ) = 2.0 * StrokeEngine.halfWidth(baseWidth, pressureEnabled, m, ds, pressure, ty)

    // --- EMA low-pass ---
    @Test fun emaFirstSamplePassesThrough() {
        assertEquals(5.0, StrokeEngine.ema(listOf(5.0, 9.0, 1.0))[0], 1e-12)
    }

    @Test fun emaTwoSamples() {
        assertEquals(listOf(0.0, 5.0), StrokeEngine.ema(listOf(0.0, 10.0)))
    }

    @Test fun emaEmpty() {
        assertEquals(emptyList<Double>(), StrokeEngine.ema(emptyList()))
    }

    // --- Width formula ---
    @Test fun penWidth() {
        // base 3, pressure on, m=0.35, ds=0
        assertEquals(1.05, width(3.0, true, 0.35, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(3.0, width(3.0, true, 0.35, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun calligraphyWidth() {
        // base 6, pressure on, m=0.40, ds=0.60
        assertEquals(0.96, width(6.0, true, 0.40, 0.60, 0.0, -1.0), 1e-9) // thinnest
        assertEquals(9.6, width(6.0, true, 0.40, 0.60, 1.0, 1.0), 1e-9)   // thickest
        val ratio = width(6.0, true, 0.40, 0.60, 1.0, 1.0) / width(6.0, true, 0.40, 0.60, 1.0, -1.0)
        assertEquals(4.0, ratio, 1e-9) // direction-only ratio
    }

    @Test fun pressureDisabledIsFullWidth() {
        // base 4, off, m=0.1, ds=0 -> full width regardless of pressure
        assertEquals(4.0, width(4.0, false, 0.1, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(4.0, width(4.0, false, 0.1, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun directionFloorClamps() {
        // base 10, off, m=1.0, ds=0.95 -> would-be-negative factor clamps to 0.1
        assertEquals(1.0, width(10.0, false, 1.0, 0.95, 1.0, -1.0), 1e-9)
    }

    // --- Geometry ---
    @Test fun singleSampleIsOneCapNoOutline() {
        val g = StrokeEngine.build(listOf(Sample(10.0, 20.0, 1.0)), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertEquals(1, g.caps.size)
        assertEquals(10.0, g.caps[0].center.x, 1e-9)
        assertEquals(20.0, g.caps[0].center.y, 1e-9)
        assertEquals(1.5, g.caps[0].radius, 1e-9) // full-pressure half-width
    }

    @Test fun threeCollinearSamples() {
        val g = StrokeEngine.build(
            listOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
            3.0, true, 0.35, 0.0,
        )
        assertTrue(g.caps.isEmpty())          // flat ends, no head/tail discs
        assertEquals(6, g.outline.size)       // 3 left edge + 3 right edge
    }

    @Test fun emptyInput() {
        val g = StrokeEngine.build(emptyList(), 3.0, true, 0.35, 0.0)
        assertTrue(g.outline.isEmpty())
        assertTrue(g.caps.isEmpty())
    }

    // --- Taper pen (§1.1) ---
    @Test fun taperSpansTheWholeStrokeFromHeadToTip() {
        // The taper eases across the entire stroke now: full width at the head, down to the tip at
        // the end (a point when tip width is 0). Pressure off, m=1 ⇒ a flat 2.0 before the taper.
        val pts = (0..10).map { Sample(it * 10.0, 0.0, 1.0) }  // total 100 px
        val plain = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0)
        val tapered = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)

        assertEquals(2.0, plain.halfWidths.first(), 1e-9)
        assertEquals(2.0, tapered.halfWidths.first(), 1e-9)  // head: full width
        assertEquals(1.0, tapered.halfWidths[5], 1e-9)       // halfway: edge 0.5 ⇒ half of full
        assertEquals(0.0, tapered.halfWidths.last(), 1e-9)   // tail: a point

        // Width eases monotonically from the head all the way to the tip.
        for (i in 0 until tapered.halfWidths.size - 1) assertTrue(tapered.halfWidths[i] > tapered.halfWidths[i + 1])
    }

    @Test fun taperIgnoresVeryShortStrokes() {
        // Total arc length < 8 px ⇒ left un-tapered (a quick tick shouldn't vanish).
        val pts = listOf(Sample(0.0, 0.0, 1.0), Sample(3.0, 0.0, 1.0))
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true)
        assertEquals(2.0, g.halfWidths.first(), 1e-9)
    }

    @Test fun taperProfileScalesWithStrokeLength() {
        // The taper spans the whole stroke, so two different-length strokes share the same width
        // at the same fractional position (it is proportional now, not a fixed arc length).
        val a = (0..10).map { Sample(it * 10.0, 0.0, 1.0) }  // total 100
        val b = (0..20).map { Sample(it * 10.0, 0.0, 1.0) }  // total 200
        val ga = StrokeEngine.build(a, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)
        val gb = StrokeEngine.build(b, 4.0, false, 1.0, 0.0, taperEnabled = true, smooth = false)
        assertEquals(ga.halfWidths[5], gb.halfWidths[10], 1e-9)          // both 50% along the stroke
        assertEquals(ga.halfWidths.first(), gb.halfWidths.first(), 1e-9) // both full at the head
        assertEquals(ga.halfWidths.last(), gb.halfWidths.last(), 1e-9)   // both a point at the tail
    }

    @Test fun taperBottomsOutAtTheTipWidthFloor() {
        // A non-zero tip width stops the tail at that fraction of full width instead of a point.
        val pts = (0..10).map { Sample(it * 10.0, 0.0, 1.0) } // total 100 px
        val g = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, taperEnabled = true, taperMinFactor = 0.25, smooth = false)
        assertEquals(2.0, g.halfWidths.first(), 1e-9)   // head: full width
        assertEquals(0.5, g.halfWidths.last(), 1e-9)    // tail: 0.25 of the 2.0 full half-width
        assertEquals(1.25, g.halfWidths[5], 1e-9)       // halfway: 0.25 + 0.75·0.5 of full
    }

    // --- Speed pen (§1.1) ---
    @Test fun speedThinsFastStrokes() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) } // ~0.1 px/ms
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }   // ~2 px/ms
        val gSlow = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0, speedStrength = 0.8)
        val gFast = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0, speedStrength = 0.8)

        assertTrue(gSlow.halfWidths.maxOrNull()!! > 1.8)          // slow: near full width
        assertTrue(gFast.halfWidths.maxOrNull()!! < 2.0)          // fast: thinned even at its widest
        assertTrue(gFast.halfWidths.minOrNull()!! < 1.0)          // and clearly thin where fastest
    }

    @Test fun speedOffIgnoresTiming() {
        val xs = (0..5).map { it * 10.0 }
        val slow = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 100.0) }
        val fast = xs.mapIndexed { i, x -> Sample(x, 0.0, 1.0, i * 5.0) }
        // speedStrength defaults to 0 ⇒ identical geometry regardless of timing.
        val a = StrokeEngine.build(slow, 4.0, false, 1.0, 0.0)
        val b = StrokeEngine.build(fast, 4.0, false, 1.0, 0.0)
        assertEquals(a.halfWidths, b.halfWidths)
    }

    @Test fun speedScaleScalesPerceivedSpeed() {
        // Same gesture and timing; a larger content->dp scale (e.g. zoomed in) reads as
        // faster, so the line thins more than the unscaled one (which stays near full width).
        val pts = (0..5).map { Sample(it * 10.0, 0.0, 1.0, it * 100.0) }
        val unscaled = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 1.0)
        val scaled = StrokeEngine.build(pts, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 5.0)
        assertTrue(unscaled.halfWidths.maxOrNull()!! > 1.8)
        assertTrue(scaled.halfWidths.maxOrNull()!! < unscaled.halfWidths.maxOrNull()!!)
    }

    @Test fun speedKeepsAnEvenWidthThroughAConstantSpeedCorner() {
        // Right then up at a steady sample rate: the nib's path length per unit time never
        // changes, so a speed-thinned line must keep an even width through the corner. Reading the
        // raw sample motion over a time window (not the corner-cutting smoothed centerline, nor a
        // sample-count window that collapses onto the bunched corner) is what keeps the corner from
        // reading a false slow-down and ballooning into a blob. speedScale puts it mid-band so any
        // dip would show.
        val right = (0..10).map { Sample(it * 8.0, 0.0, 1.0, it * 8.0) }
        val up = (1..10).map { Sample(80.0, -it * 8.0, 1.0, (10 + it) * 8.0) }
        val g = StrokeEngine.build(right + up, 4.0, false, 1.0, 0.0, speedStrength = 0.8, speedScale = 0.5)
        val maxHw = g.halfWidths.maxOrNull()!!
        val minHw = g.halfWidths.minOrNull()!!
        assertTrue("partially thinned, not saturated", maxHw < 1.9 && minHw > 0.5)
        assertEquals("even width through the corner", maxHw, minHw, 1e-6)
    }

    // --- Flat ends & direction smoothing ---
    @Test fun calligraphyEndsAreFlatNotRoundCapped() {
        // A multi-sample calligraphy ribbon ends square: no head/tail cap discs, so it never
        // sprouts a rounded dot past the thinned nib. The ribbon half-widths are unaffected.
        val pts = (0..6).map { Sample(0.0, -it * 10.0, 1.0) } // straight up
        val g = StrokeEngine.build(pts, 6.0, true, 0.40, 0.60)
        assertTrue(g.caps.isEmpty())
        assertTrue("upward calligraphy ribbon is thinned by the direction term",
            g.halfWidths.first() < 1.5)
    }

    @Test fun highlighterKeepsRoundEndCaps() {
        // The highlighter is the one ribbon pen that still rounds its ends: roundCaps = true emits
        // a head and tail disc sized to the ribbon's half-width.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val g = StrokeEngine.build(pts, 16.0, false, 1.0, 0.0, roundCaps = true)
        assertEquals(2, g.caps.size)
        assertEquals(8.0, g.caps[0].radius, 1e-9) // 16 × 1.0 / 2
        assertEquals(8.0, g.caps[1].radius, 1e-9)
    }

    @Test fun penKeepsRoundEndCaps() {
        // The regular pen rounds its ends like the highlighter: roundCaps = true emits a head and
        // tail disc sized to the ribbon's half-width, which (ds = 0) is the pure-pressure value.
        val pts = (0..4).map { Sample(it * 10.0, 0.0, 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, roundCaps = true)
        assertEquals(2, g.caps.size)
        assertEquals(1.5, g.caps[0].radius, 1e-9) // 3.0 × 1.0 / 2
        assertEquals(1.5, g.caps[1].radius, 1e-9)
    }

    @Test fun penRoundCapsDoNotPinchAtLightPenDownAndUp() {
        // Pen-down and pen-up samples arrive light; without the end-width hold the cap would shrink
        // to ~0.62 (the 0.1-pressure tip) against a ~1.5 body. The hold lifts the ends to the
        // settled body pressure so each cap meets the line at nearly full width (no pinch), and
        // never overshoots it (no bulge past the ribbon).
        val pts = (0..11).map { i -> Sample(i * 10.0, 0.0, if (i == 0 || i == 11) 0.1 else 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, roundCaps = true)
        val body = g.halfWidths.maxOrNull()!!
        assertEquals(2, g.caps.size)
        assertTrue("head cap should not pinch to the light tip", g.caps[0].radius > 1.4)
        assertTrue("tail cap should not pinch to the light tip", g.caps[1].radius > 1.4)
        assertTrue("caps never exceed the body width", g.caps[0].radius <= body + 1e-9)
        assertTrue(g.caps[1].radius <= body + 1e-9)
    }

    @Test fun penHoldOnlyRaisesTheEndsNeverThinsTheMiddle() {
        // The hold only lifts the end samples up to the inner body width; a mid-stroke pressure dip
        // is left alone, so the ribbon still narrows in the middle where the pen was pressed lighter.
        val pts = (0..11).map { i -> Sample(i * 10.0, 0.0, if (i in 5..6) 0.2 else 1.0) }
        val g = StrokeEngine.build(pts, 3.0, true, 0.35, 0.0, roundCaps = true)
        assertTrue("ends held to the body width", g.caps[0].radius > 1.4 && g.caps[1].radius > 1.4)
        assertTrue("mid-stroke dip survives", g.halfWidths.minOrNull()!! < 1.2)
    }

    @Test fun calligraphyWidthGlidesAcrossADirectionChange() {
        // The nib width is low-passed, so when an L-stroke turns from a long rightward run
        // (thick horizontal regime) into a long upward run (thin vertical regime), the width
        // keeps easing down for several samples past the corner instead of snapping the
        // instant the tangent flips. Without the direction low-pass the upward samples would
        // all sit at the thin regime immediately.
        val pts = (0..9).map { Sample(it * 10.0, 0.0, 1.0) } +
            (1..14).map { Sample(90.0, -it * 10.0, 1.0) }
        val g = StrokeEngine.build(pts, 6.0, true, 0.40, 0.60)
        val corner = 10 // first sample of the upward run
        val settled = g.halfWidths.last()
        assertTrue("width should still be mid-transition just past the corner",
            g.halfWidths[corner + 1] > settled + 1e-6)
        assertTrue("width should still be easing several samples past the corner",
            g.halfWidths[corner + 3] > settled + 1e-6)
        assertTrue("and the transition is monotone (no snap-back)",
            g.halfWidths[corner + 1] >= g.halfWidths[corner + 3] - 1e-9)
        assertTrue("ends thinner than it started", settled < g.halfWidths.first())
    }
}
