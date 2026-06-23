package com.xnotes.core.stroke

import com.xnotes.core.geometry.Pt
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw stylus samples into a smooth, variable-width ink ribbon (spec 03).
 * Pure, deterministic and unit-tested against the spec's conformance vectors.
 */
object StrokeEngine {
    /** EMA low-pass smoothing factor (1.0 = passthrough, ->0 = heavy lag). */
    const val ALPHA = 0.5

    /** Below this difference length a sample is degenerate; reuse last tangent. */
    const val MIN_TANGENT_LEN = 1e-6

    /** Floor on the calligraphic direction term so width stays positive. */
    const val MIN_DIRECTION = 0.1

    /** Calligraphy pen: a heavier low-pass on the direction channel (the tangent's y that
     *  drives nib width) than [ALPHA] (position), so the width eases between thick and thin
     *  as the stroke curves instead of snapping when the tangent turns. The speed pen smooths
     *  its width the same spirit via a windowed velocity (see [speedFactors]); only the width
     *  magnitude is smoothed, not the ribbon's orientation. */
    const val DIR_ALPHA = 0.25

    /** Speed pen: dp/ms at/below which the line stays full width, and the speed
     *  at/above which it reaches its thinnest (≈1.25 and ≈5 in/s of hand travel).
     *  Measuring in dp — not page pixels — makes the effect independent of both zoom
     *  and screen density; see [speedFactors] and the per-stroke speed scale. */
    const val SPEED_LO = 0.2
    const val SPEED_HI = 0.8

    /** Speed pen: half the duration (ms) of the centred window the nib's speed is measured over.
     *  Speed is the arc length covered across `±this` ms divided by that span. A fixed *time*
     *  base (not a fixed sample count, which collapses to a point where the pen crawls and the
     *  distance-gated samples bunch up) keeps the estimate steady and lets the faster ink on
     *  either side of a brief corner pause dilute it, instead of the width ballooning into a
     *  blob there. The window slides inward at the stroke's ends so its first and last points
     *  still average a full span rather than the at-rest tip. */
    const val SPEED_WINDOW_MS = 40.0

    /** Speed pen: minimum per-segment dt (ms) so a duplicate-timestamp pair can't
     *  divide by ~zero and spike the speed. */
    const val MIN_DT = 1.0

    /** Taper pen: strokes shorter than this (page px of arc length) are left
     *  un-tapered, so a quick tick doesn't collapse to nothing. */
    const val TAPER_MIN_LEN = 8.0

    /** Taper pen: the taper at each end is capped at this fraction of the stroke's own length,
     *  so a large taper value on a short stroke only eats this much per end instead of collapsing
     *  the whole stroke into a point. With the cap at 0.1 the middle 80% always reaches full
     *  width; a value that already fits under the cap is used unchanged (a fixed arc length). */
    const val TAPER_MAX_FRACTION = 0.1

    /** One-pole IIR low-pass (exponential moving average). */
    fun ema(values: List<Double>, alpha: Double = ALPHA): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        }
        return out.asList()
    }

    /**
     * Half-width at a point (spec 03 step 5), given smoothed [pressure] and the
     * tangent's y-component [ty]. The pure-pressure half-width (caps and the
     * single-sample dot) uses `ty = 0`.
     */
    fun halfWidth(
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        pressure: Double,
        ty: Double,
    ): Double {
        val pEff = if (pressureEnabled) pressure else 1.0
        val wBase = baseWidth * (m + (1 - m) * pEff)
        val direction = max(1 + ds * ty, MIN_DIRECTION)
        return wBase * direction / 2.0
    }

    /** Hermite smoothstep: 0 below [lo], 1 above [hi], an S-curve between. */
    private fun smoothstep(lo: Double, hi: Double, x: Double): Double {
        if (hi <= lo) return if (x >= hi) 1.0 else 0.0
        val t = ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * Per-point width multipliers in `[1 − speedStrength, 1]` for the **speed pen**:
     * the faster the nib travels across the page, the thinner the line (ink has less
     * time to lay down). Speed at point `i` is the **arc length of the raw samples over a
     * centred time window** of `±[SPEED_WINDOW_MS]` ms divided by that span, in dp/ms, where
     * [speedScale] (zoom ÷ density, captured at pen-down) converts page pixels to dp so the
     * effect is zoom- and device-independent. It reads the raw sample motion, not the smoothed
     * centerline, so the position low-pass can't compress the start or cut a corner short and
     * read a false slow-down there. Summing distance and time over a fixed *time* span (not a
     * fixed sample count) rejects per-sample jitter and keeps slow corners and ends from
     * collapsing the window onto themselves and ballooning the width. Returns all-`1.0` when off
     * or the samples carry no usable timing.
     */
    fun speedFactors(samples: List<Sample>, speedStrength: Double, speedScale: Double): List<Double> {
        val n = samples.size
        if (speedStrength <= 0.0 || n < 2) return List(n) { 1.0 }
        val t0 = samples.first().t
        val tN = samples.last().t
        if (tN - t0 <= 0.0) return List(n) { 1.0 }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + (samples[i].pos - samples[i - 1].pos).length()
        val half = SPEED_WINDOW_MS
        var lo = 0
        var hi = 0
        return (0 until n).map { i ->
            // Centre a fixed-duration window on this sample's time; if it runs past either end of
            // the stroke, slide it inward so the span stays ~2·half rather than shrinking to a point.
            var a = samples[i].t - half
            var b = samples[i].t + half
            if (a < t0) { b += t0 - a; a = t0 }
            if (b > tN) { a -= b - tN; b = tN; if (a < t0) a = t0 }
            while (lo < i && samples[lo].t < a) lo++
            while (hi < n - 1 && samples[hi + 1].t <= b) hi++
            // Always span at least one segment so a window that falls between two far-apart slow
            // samples reads a real speed instead of a zero-length divide.
            var l = lo
            var h = hi
            if (h <= l) { if (h < n - 1) h++ else l-- }
            val dist = (cum[h] - cum[l]) * speedScale
            val dt = max(samples[h].t - samples[l].t, MIN_DT)
            1.0 - speedStrength * smoothstep(SPEED_LO, SPEED_HI, dist / dt)
        }
    }

    /**
     * Per-point width multipliers in `[0, 1]` for the **taper pen**: the line eases
     * out of a point at each end and reaches full width in the middle. [taperLength]
     * is the arc length (content px) over which each end eases in, but it is capped at
     * [TAPER_MAX_FRACTION] of the stroke's own length so a large value on a short stroke only
     * tapers that fraction per end instead of collapsing the whole stroke into a point. Above
     * that the value is used unchanged (a fixed arc length, so the taper keeps its size as a long
     * stroke grows). Returns all-`1.0` when off or the stroke is too short.
     */
    fun taperFactors(centers: List<Pt>, taperLength: Double): List<Double> {
        val n = centers.size
        if (taperLength <= 0.0 || n < 2) return List(n) { 1.0 }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + (centers[i] - centers[i - 1]).length()
        val total = cum[n - 1]
        if (total < TAPER_MIN_LEN) return List(n) { 1.0 }
        val taper = min(taperLength, TAPER_MAX_FRACTION * total)
        return (0 until n).map { i ->
            // Arc distance to the nearer end, ramped over the (capped) taper length.
            val edge = (min(cum[i], total - cum[i]) / taper).coerceIn(0.0, 1.0)
            edge * edge * (3 - 2 * edge)
        }
    }

    /**
     * Builds [StrokeGeometry] from [samples] and the style fields. [speedStrength]
     * and [taperLength] default to off, in which case the output is identical to
     * the four-field pen/calligraphy pipeline (spec 03 conformance).
     */
    fun build(
        samples: List<Sample>,
        baseWidth: Double,
        pressureEnabled: Boolean,
        m: Double,
        ds: Double,
        speedStrength: Double = 0.0,
        taperLength: Double = 0.0,
        speedScale: Double = 1.0,
        smooth: Boolean = true,
        roundCaps: Boolean = false,
    ): StrokeGeometry {
        val n = samples.size
        if (n == 0) return StrokeGeometry.EMPTY

        // 2. Smooth each channel independently. Straight-line strokes skip the position low-pass
        //    so the ribbon spans the raw samples exactly (EMA would pull a 2-point line's far end
        //    toward the midpoint, leaving it short of the pointer).
        val sx = if (smooth) ema(samples.map { it.x }) else samples.map { it.x }
        val sy = if (smooth) ema(samples.map { it.y }) else samples.map { it.y }
        val sp = ema(samples.map { it.pressure })
        val centers = (0 until n).map { Pt(sx[it], sy[it]) }

        fun hw(i: Int, ty: Double) = halfWidth(baseWidth, pressureEnabled, m, ds, sp[i], ty)

        // 3. Single sample -> a filled dot (pure-pressure half-width, no direction).
        if (n == 1) {
            val h = hw(0, 0.0)
            return StrokeGeometry(emptyList(), listOf(Cap(centers[0], h)), centers, listOf(h))
        }

        // 4. Per-point unit tangent via finite differences.
        var lastGood = Pt(1.0, 0.0)
        val tangents = ArrayList<Pt>(n)
        for (i in 0 until n) {
            val diff = when (i) {
                0 -> centers[1] - centers[0]
                n - 1 -> centers[i] - centers[i - 1]
                else -> centers[i + 1] - centers[i - 1]
            }
            val len = diff.length()
            val t = if (len < MIN_TANGENT_LEN) lastGood else (diff / len).also { lastGood = it }
            tangents.add(t)
        }

        // Optional width multipliers: speed thins fast travel, taper points the ends.
        val sf = speedFactors(samples, speedStrength, speedScale)
        val tf = taperFactors(centers, taperLength)

        // Calligraphy: low-pass the direction channel (the tangent's y that sets nib width)
        // so the width glides between thick and thin instead of snapping when the stroke
        // curves. Only the width magnitude is smoothed — the ribbon's orientation still
        // follows the true tangent. A no-op when ds = 0 (no calligraphic direction effect).
        val dirY = if (ds > 0.0) ema(tangents.map { it.y }, DIR_ALPHA) else null

        // 5–7. Half-widths, normals, and the two ribbon edges.
        val left = ArrayList<Pt>(n)
        val right = ArrayList<Pt>(n)
        val halfWidths = ArrayList<Double>(n)
        for (i in 0 until n) {
            val t = tangents[i]
            val h = hw(i, dirY?.get(i) ?: t.y) * sf[i] * tf[i]
            halfWidths.add(h)
            val normal = Pt(-t.y, t.x) // tangent rotated 90°, already unit length
            left.add(centers[i] - normal * h)
            right.add(centers[i] + normal * h)
        }

        // 8. Outline = left in order + right reversed (single closed polygon).
        val outline = ArrayList<Pt>(2 * n)
        outline.addAll(left)
        for (i in right.indices.reversed()) outline.add(right[i])

        // 9. End caps only when [roundCaps] is set (the highlighter): two discs sized to the
        //    ribbon's own half-width round the head and tail off. Every other pen leaves its ends
        //    flat (butt), and the taper pen already came to a point. A lone tap is still a round
        //    dot (the n == 1 path above).
        val caps = if (roundCaps) listOf(
            Cap(centers[0], halfWidths[0]),
            Cap(centers[n - 1], halfWidths[n - 1]),
        ) else emptyList()

        return StrokeGeometry(
            outline = if (outline.size >= 3) outline else emptyList(),
            caps = caps,
            centerline = centers,
            halfWidths = halfWidths,
        )
    }
}
