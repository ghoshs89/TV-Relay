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
import androidx.compose.ui.graphics.StrokeCap
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
                "Live TV  ·  Movies  ·  Series", fontSize = 16.sp, color = Palette.dim,
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
        Text(
            "v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Palette.dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).graphicsLayer { alpha = fade.value },
        )
    }
}

/** The app's signal mark: two arcs and a dot, drawn progressively. */
@Composable
fun RelayMark(modifier: Modifier, progress: Float) {
    Canvas(modifier) {
        val w = size.minDimension
        val cx = size.width / 2
        val cy = size.height * 0.64f
        val stroke = w * 0.09f
        listOf(Palette.accent2 to 0.46f, Palette.accent to 0.29f).forEach { (color, radius) ->
            val r = w * radius
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f * progress,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(Palette.accent, radius = w * 0.075f * progress, center = Offset(cx, cy + w * 0.02f))
    }
}
