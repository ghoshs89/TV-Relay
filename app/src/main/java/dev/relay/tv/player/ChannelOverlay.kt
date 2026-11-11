package dev.relay.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.tv.RelayApp
import dev.relay.tv.data.PlayItem
import dev.relay.tv.ui.Focusable
import dev.relay.tv.ui.InitialFocus
import dev.relay.tv.ui.LogoImage
import dev.relay.tv.ui.Palette
import dev.relay.tv.ui.initials

/** Channel list drawn over live video. OK picks a channel; Back closes it. */
@Composable
fun ChannelOverlay(items: List<PlayItem>, current: Int, onPick: (Int) -> Unit) {
    val app = RelayApp.instance
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    InitialFocus(currentFocus)

    // Fetch "now playing" for the rows around the current channel.
    var loaded by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val around = items.subList((current - 6).coerceAtLeast(0), (current + 10).coerceAtMost(items.size))
        around.forEach {
            it.streamId.toIntOrNull()?.let { id -> app.catalog.shortEpg(id) }
            loaded++
        }
    }
    loaded // read, so the rows pick up each programme title as it arrives

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color(0xEE000000), 0.45f to Color(0x99000000), 0.7f to Color.Transparent)),
    ) {
        Column(Modifier.width(440.dp).fillMaxHeight().padding(start = 32.dp, top = 28.dp, bottom = 20.dp)) {
            Text("Channels", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("OK to watch  ·  Back to close", color = Palette.dim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                state = state,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                itemsIndexed(items) { i, item ->
                    val playing = i == current
                    Focusable(
                        { onPick(i) },
                        Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(8.dp),
                        scaleOnFocus = 1.02f,
                        focusRequester = if (playing) currentFocus else null,
                    ) { f ->
                        Row(
                            Modifier
                                .fillMaxSize()
                                .background(if (f) Palette.surface2 else if (playing) Palette.surface else Color(0x66000000))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.subtitle ?: "", Modifier.width(44.dp), color = Palette.dim, fontSize = 13.sp)
                            Box(
                                Modifier.size(width = 58.dp, height = 38.dp).background(Color(0xFFF2F5FA), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                LogoImage(item.image, Modifier.fillMaxSize().padding(3.dp)) {
                                    Text(initials(item.title), color = Color(0xFF5B6B85), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title, color = if (playing) Palette.accent else Palette.text, fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                item.streamId.toIntOrNull()?.let { app.catalog.nowPlaying(it) }?.let {
                                    Text(it, color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
