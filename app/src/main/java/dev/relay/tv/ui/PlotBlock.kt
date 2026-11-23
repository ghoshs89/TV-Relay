package dev.relay.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A title's summary on its page, a few lines long. When it doesn't fit, it can be reached with the remote,
 * says **More**, and OK (or a tap) opens the whole text. When it fits, it is plain text the remote skips.
 */
@Composable
fun PlotBlock(plot: String, title: String, maxLines: Int, fontSize: TextUnit, lineHeight: TextUnit, key: String) {
    var cut by remember(plot, maxLines) { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    if (cut) {
        Focusable({ open = true }, Modifier.fillMaxWidth(), key = key, shape = RoundedCornerShape(8.dp), scaleOnFocus = 1f) { f ->
            Column(Modifier.fillMaxWidth().background(if (f) Palette.surface2 else Color.Transparent).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    plot, color = Palette.text, fontSize = fontSize, lineHeight = lineHeight,
                    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "More", Modifier.padding(top = 4.dp), color = if (f) Palette.accent else Palette.dim,
                    fontSize = Type.caption, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    } else {
        Text(
            plot, color = Palette.text, fontSize = fontSize, lineHeight = lineHeight,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow) cut = true },
        )
    }
    if (open) PlotDialog(title, plot) { open = false }
}

/**
 * The whole summary. The text area scrolls with Up and Down (a remote has nothing else to scroll with);
 * at its end Down moves on to **Close**.
 */
@Composable
private fun PlotDialog(title: String, plot: String, onDismiss: () -> Unit) {
    val textFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    InitialFocus(textFocus)
    TvDialog(onDismiss, width = 720.dp) {
        Text(displayTitle(title), fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .focusRequester(textFocus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> if (scroll.canScrollForward) { scope.launch { scroll.animateScrollBy(160f) }; true } else false
                        Key.DirectionUp -> if (scroll.canScrollBackward) { scope.launch { scroll.animateScrollBy(-160f) }; true } else false
                        else -> false
                    }
                }
                .focusable()
                .verticalScroll(scroll),
        ) {
            Text(plot, color = Palette.text, fontSize = Type.bodyLarge, lineHeight = Type.bodyLarge * 1.5f)
        }
        PillButton("Close", onDismiss, key = "plot:close")
    }
}
