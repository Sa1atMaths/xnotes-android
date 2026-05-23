package com.xnotes.ui

import android.graphics.drawable.AnimationDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R

/**
 * Fullscreen launch animation: the exported frame-by-frame glitch loader
 * ([R.drawable.xnotes_loader] — a 24-frame ~1.6s [AnimationDrawable]) centred on a
 * stage that reproduces the frames' baked-in background exactly — the `#0E1218`
 * base plus the same radial vignette, continued out past the square so its
 * darkened edge blends into the background with no visible seam. Shown while the
 * session restores, then faded out.
 */
@Composable
fun XnotesLoader(modifier: Modifier = Modifier) {
    val stage = Color(0xFF0E1218) // the frames' baked-in centre colour
    val cfg = LocalConfiguration.current
    val side = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.7f).dp
    Box(
        modifier
            .fillMaxSize()
            .background(stage)
            .drawBehind {
                // The frames' vignette runs transparent (centre) → ~36% black at the
                // square's far corner (half-diagonal). Reproduce it at the same scale
                // so the area around the image matches the image's own edges.
                drawRect(
                    Brush.radialGradient(
                        0.40f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.36f),
                        center = center,
                        radius = side.toPx() / 2f * 1.41421f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx -> ImageView(ctx).apply { setImageResource(R.drawable.xnotes_loader) } },
            update = { iv -> (iv.drawable as? AnimationDrawable)?.let { if (!it.isRunning) it.start() } },
            modifier = Modifier.size(side),
        )
    }
}
