package com.xnotes.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine transform mapping `(x, y) -> (a*x + c*y + e, b*x + d*y + f)`, used to bake a
 * selection scale or rotation into item geometry. The canvas [com.xnotes.core.pal.Renderer] is
 * deliberately axis-aligned (translate + scale only, never rotation), so a rotation is never a
 * render-time transform: it is baked into the actual points through this class instead.
 */
data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun apply(p: Pt): Pt = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** Linear scale of each axis (sign-free); a pure rotation gives 1.0, 1.0. */
    val scaleX: Double get() = hypot(a, b)
    val scaleY: Double get() = hypot(c, d)

    /** Signed area scale; the uniform linear factor is `sqrt(|determinant|)`. */
    val determinant: Double get() = a * d - b * c

    /** Uniform linear scale factor (the geometric mean of the axis scales). 1.0 for a rotation. */
    val linearScale: Double get() = sqrt(abs(determinant))

    /** No rotation or shear: a pure scale-and-translate (the off-diagonal terms are zero). */
    val isAxisAligned: Boolean get() = b == 0.0 && c == 0.0

    /** An axis-aligned scale with equal factors on both axes. */
    val isUniformScale: Boolean get() = isAxisAligned && a == d

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** Scale by ([sx], [sy]) about [pivot], which stays fixed. */
        fun scaleAbout(pivot: Pt, sx: Double, sy: Double): Affine =
            Affine(sx, 0.0, 0.0, sy, pivot.x * (1.0 - sx), pivot.y * (1.0 - sy))

        /** Rotate by [radians] (clockwise in screen space) about [center], which stays fixed. */
        fun rotateAbout(center: Pt, radians: Double): Affine {
            val cs = cos(radians)
            val sn = sin(radians)
            return Affine(
                cs, sn, -sn, cs,
                center.x - cs * center.x + sn * center.y,
                center.y - sn * center.x - cs * center.y,
            )
        }
    }
}
