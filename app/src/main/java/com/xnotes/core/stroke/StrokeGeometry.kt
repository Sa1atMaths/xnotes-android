package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt

/**
 * One captured stylus point, page-local; `pressure` in `[0, 1]`. [t] is the
 * milliseconds elapsed since the stroke's first sample (0 for that first one),
 * used only by velocity-aware tools (the speed pen); 0 everywhere else.
 */
data class Sample(val x: Double, val y: Double, val pressure: Double, val t: Double = 0.0) {
    val pos: Pt get() = Pt(x, y)
}

/**
 * The geometry derived from a stroke's samples (spec 03). The ink is painted by sweeping a brush
 * disc down the [centerline] at the per-point [halfWidths] (Renderer.fillDiskRibbon), so caps and
 * joins round on every pen; [outline] is that same ribbon as one closed polygon, kept for the neon
 * bloom and hit-testing.
 */
data class StrokeGeometry(
    val outline: List<Pt>,
    val centerline: List<Pt>,
    val halfWidths: List<Double>,
) {
    companion object {
        val EMPTY = StrokeGeometry(emptyList(), emptyList(), emptyList())
    }
}
