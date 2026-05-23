package com.xnotes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Fullscreen launch animation (recreates `XNotes Loader.html`): the glitchy
 * `xnotes` wordmark — the brand X in phosphor green with RGB-split (red/cyan)
 * slice glitches, jitter and a colour flicker, "notes" calm beside it — over the
 * dark `#0d1117` stage with a vignette, CRT scanlines, a `$ loading` tagline and an
 * indeterminate progress bar. Shown while the session restores, then faded out.
 */
@Composable
fun XnotesLoader(modifier: Modifier = Modifier) {
    val bg = Color(0xFF0D1117)
    val fg = Color(0xFFF6F4EF)
    val green = Color(0xFF3DDC7E)
    val red = Color(0xFFFF2542)
    val cyan = Color(0xFF00E5FF)

    val transition = rememberInfiniteTransition(label = "loader")
    // One ~1.6s sawtooth drives the whole glitch loop (mirrors --dur in the design).
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    // Independent 1.2s sweep for the progress bar.
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    // Glitch bursts happen in three windows of the loop; calm otherwise.
    val burst = phase in 0.15f..0.27f || phase in 0.45f..0.49f || phase in 0.65f..0.70f
    val step = (phase * 877f).toInt() // fast-changing seed for chaotic slices during a burst
    val redTop = bandTop(step)
    val cyanTop = bandTop(step + 3)
    val redDx = if (burst) 6f + (step % 3) * 2f else 0f
    val cyanDx = if (burst) -(5f + (step % 2) * 2f) else 0f
    val jitterX = if (burst) ((step % 5) - 2).toFloat() else 0f
    val jitterY = if (burst) (((step / 2) % 3) - 1).toFloat() else 0f
    val baseRed = burst && step % 2 == 0
    val ghostAlpha = if (burst) 1f else 0f
    val caretOn = burst || phase % 0.25f < 0.12f
    val notesDx = if (phase in 0.19f..0.21f || phase in 0.61f..0.63f) 1f else 0f

    val fs = (LocalConfiguration.current.screenWidthDp * 0.14f).coerceIn(64f, 168f)
    val wordSize = fs.sp

    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                // Vignette: clear centre, darkened edges.
                drawRect(
                    Brush.radialGradient(
                        0.40f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.55f),
                        center = center,
                        radius = size.maxDimension * 0.72f,
                    ),
                )
            }
            .drawWithContent {
                drawContent()
                // Faint CRT scanlines over everything.
                val gap = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(Color.White.copy(alpha = 0.022f), topLeft = Offset(0f, y), size = Size(size.width, 1f))
                    y += gap
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // The X: base glyph + two RGB-split ghosts + a blinking caret.
            Box(
                Modifier.offset(x = jitterX.dp, y = jitterY.dp).wrapContentSize(),
                contentAlignment = Alignment.TopStart,
            ) {
                GhostX(cyan, wordSize, cyanDx, cyanTop, ghostAlpha * 0.85f)
                GhostX(red, wordSize, redDx, redTop, ghostAlpha)
                Glyph("x", if (baseRed) red else green, wordSize)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (fs * 0.04f).dp, y = -(fs * 0.06f).dp)
                        .size((fs * 0.07f).dp, (fs * 0.6f).dp)
                        .background(if (caretOn) red else Color.Transparent),
                )
            }
            Box(Modifier.offset(x = notesDx.dp)) { Glyph("notes", fg, wordSize) }
        }

        // $ loading tagline with a blinking block caret.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("\$ loading", color = green, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .offset(y = -(2.dp))
                    .size(8.dp, 18.dp)
                    .background(if (sweep < 0.5f) green else Color.Transparent),
            )
        }

        // Indeterminate progress bar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .width(220.dp)
                .height(2.dp)
                .background(fg.copy(alpha = 0.08f))
                .clip(BandShape(0f, 1f)),
        ) {
            val travel by transition.animateFloat(
                initialValue = -0.4f, targetValue = 1.0f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
                label = "prog",
            )
            Box(
                Modifier
                    .offset(x = (travel * 220).dp)
                    .width(88.dp)
                    .height(2.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, green, Color.Transparent))),
            )
        }
    }
}

@Composable
private fun Glyph(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit) {
    Text(text, color = color, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-0.04).em)
}

/** A ghost copy of the X: a horizontal slice, offset sideways, in [color] at [alpha]. */
@Composable
private fun GhostX(color: Color, size: androidx.compose.ui.unit.TextUnit, dx: Float, top: Float, alpha: Float) {
    Text(
        "x",
        color = color.copy(alpha = alpha),
        fontSize = size,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.04).em,
        modifier = Modifier.offset(x = dx.dp).clip(BandShape(top, (top + 0.2f).coerceAtMost(1f))),
    )
}

private fun bandTop(seed: Int): Float = ((seed * 0.37f) % 1f).coerceIn(0f, 0.78f)

/** Clips an element to a horizontal band between [top] and [bottom] fractions of its height. */
private class BandShape(private val top: Float, private val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, size.height * top, size.width, size.height * bottom))
}
