package dev.relay.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Score

/** Relay's own simple marks, not Rotten Tomatoes' logos. */
val RtRed = Color(0xFFE5482D)
val RtGreen = Color(0xFF5BB85D)
private val Popcorn = Color(0xFFF5C451)
private val ImdbYellow = Color(0xFFF5C518)

/** "Critics 93%   Audience 88%   Rotten Tomatoes" under a title. Shows nothing without a score. */
@Composable
fun RtScores(score: Score?) {
    if (score == null || score.none) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        score.critics?.let { c ->
            TomatoMark(fresh = c >= 60)
            Spacer(Modifier.width(6.dp))
            Text("Critics $c%", color = Palette.text, fontSize = Type.body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(18.dp))
        }
        score.audience?.let { a ->
            PopcornMark(full = a >= 60)
            Spacer(Modifier.width(6.dp))
            Text("Audience $a%", color = Palette.text, fontSize = Type.body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(18.dp))
        }
        if (score.critics != null || score.audience != null) {
            Text("Rotten Tomatoes", color = Palette.dim, fontSize = Type.label)
        } else {
            score.imdb?.let { i ->
                // No Rotten Tomatoes score (common for Hindi and Bengali films): show IMDb instead.
                Text(
                    "IMDb", Modifier.background(ImdbYellow, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                    color = Color.Black, fontSize = Type.label, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Text("%.1f".format(i), color = Palette.text, fontSize = Type.body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A red tomato for 60% or higher, a green splat below. */
@Composable
private fun TomatoMark(fresh: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2 + r * 0.08f)
        if (fresh) {
            drawCircle(RtRed, r * 0.86f, c)
            drawOval(RtGreen, Offset(c.x - r * 0.45f, c.y - r * 1.02f), Size(r * 0.9f, r * 0.36f))
        } else {
            drawCircle(RtGreen, r * 0.55f, c)
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0 + 15)
                drawCircle(RtGreen, r * 0.26f, Offset(c.x + (r * 0.66f * Math.cos(a)).toFloat(), c.y + (r * 0.66f * Math.sin(a)).toFloat()))
            }
        }
    }
}

/** A popcorn tub: full when the audience score is 60% or higher, tipped over below. */
@Composable
private fun PopcornMark(full: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val tub = Path().apply {
            moveTo(w * 0.18f, h * 0.42f)
            lineTo(w * 0.82f, h * 0.42f)
            lineTo(w * 0.70f, h * 0.98f)
            lineTo(w * 0.30f, h * 0.98f)
            close()
        }
        if (!full) {
            drawPath(tub, RtGreen)
            return@Canvas
        }
        drawCircle(Popcorn, w * 0.16f, Offset(w * 0.32f, h * 0.34f))
        drawCircle(Popcorn, w * 0.18f, Offset(w * 0.52f, h * 0.24f))
        drawCircle(Popcorn, w * 0.16f, Offset(w * 0.70f, h * 0.34f))
        drawPath(tub, RtRed)
    }
}

/** Poster badge from a score saved earlier: never triggers a lookup. */
fun posterScore(kind: String, id: Int): Pair<String, Color>? {
    val app = RelayApp.instance
    val s = app.store.settings
    if (!s.posterScores || s.ratingsKey.isBlank()) return null
    val c = app.ratings.saved("$kind:$id")?.critics ?: return null
    return "$c%" to if (c >= 60) RtRed else RtGreen
}
