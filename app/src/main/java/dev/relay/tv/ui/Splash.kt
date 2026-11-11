package dev.relay.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Brief branded intro. The library keeps loading in the background meanwhile. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val draw = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, tween(600)) }
        draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        delay(550)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Palette.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RelayMark(Modifier.size(140.dp), draw.value)
            Spacer(Modifier.height(18.dp))
            Text(
                "Relay", fontSize = 54.sp, fontWeight = FontWeight.ExtraBold, color = Palette.text,
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
            Text(
                "Live TV  ·  Movies  ·  Series", fontSize = Type.bodyLarge, color = Palette.dim,
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
        Text(
            "v${BuildConfig.VERSION_NAME}", fontSize = Type.label, color = Palette.dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).graphicsLayer { alpha = fade.value },
        )
    }
}

/** Relay's mark (same as the launcher icon): a TV screen, a play button and an on-air dot, built up as [progress] goes from 0 to 1. */
@Composable
fun RelayMark(modifier: Modifier, progress: Float) {
    Canvas(modifier) {
        // Drawn in the icon's 72-unit grid.
        val u = size.minDimension / 72f
        val ox = (size.width - 72f * u) / 2
        val oy = (size.height - 72f * u) / 2
        fun at(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)

        val frame = Palette.text.copy(alpha = (progress / 0.5f).coerceIn(0f, 1f))
        drawRoundRect(
            frame, topLeft = at(14f, 19f), size = Size(44f * u, 30f * u),
            cornerRadius = CornerRadius(5f * u), style = Stroke(width = 4f * u),
        )
        drawRoundRect(frame, topLeft = at(28f, 52f), size = Size(16f * u, 3.5f * u), cornerRadius = CornerRadius(1.75f * u))

        val play = ((progress - 0.25f) / 0.55f).coerceIn(0f, 1f)
        if (play > 0f) {
            // Grows from its centre while fading in.
            val k = 0.6f + 0.4f * play
            val cx = (31.5f + 44f + 31.5f) / 3
            val cy = 34f
            fun pt(x: Float, y: Float) = at(cx + (x - cx) * k, cy + (y - cy) * k)
            val tri = Path().apply {
                val a = pt(31.5f, 27.5f)
                val b = pt(44f, 34f)
                val c = pt(31.5f, 40.5f)
                moveTo(a.x, a.y)
                lineTo(b.x, b.y)
                lineTo(c.x, c.y)
                close()
            }
            val color = Palette.accent.copy(alpha = play)
            drawPath(tri, color)
            drawPath(tri, color, style = Stroke(width = 3f * u * k, join = StrokeJoin.Round))
        }

        val dot = ((progress - 0.75f) / 0.25f).coerceIn(0f, 1f)
        if (dot > 0f) {
            drawCircle(Palette.bg, radius = 6.75f * u * dot, center = at(55f, 19f))
            drawCircle(LiveRed, radius = 5.5f * u * dot, center = at(55f, 19f))
        }
    }
}

private val LiveRed = Color(0xFFFF5A4E)
