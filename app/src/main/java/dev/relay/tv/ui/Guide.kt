package dev.relay.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Channel
import dev.relay.tv.data.EpgEntry
import dev.relay.tv.player.Playback
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.filled.PlayArrow

private const val DP_PER_MIN = 4f // 1 hour = 240 dp
private const val HOURS_BACK = 3
private const val HOURS_AHEAD = 8
private val CHANNEL_COL = 170.dp
private val ROW_HEIGHT = 60.dp

/**
 * TiviMate-style guide: channels down the side, time across. D-pad moves cell to cell and the
 * timeline follows focus; phones can drag it sideways. OK on a past show opens catch-up, on the
 * current show plays the channel, on a future show sets or clears a reminder.
 */
@Composable
fun GuideGrid(channels: List<Channel>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    // Rows reload their listings every 15 minutes while the guide stays open.
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var minutes = 0
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis() / 1000
            if (++minutes % 15 == 0) reload++
        }
    }
    val windowStart = remember { (now - HOURS_BACK * 3600) / 1800 * 1800 }
    val windowEnd = windowStart + (HOURS_BACK + HOURS_AHEAD) * 3600
    val totalMin = ((windowEnd - windowStart) / 60).toFloat()
    // Minutes from windowStart at the left edge of the timeline.
    var offsetMin by remember { mutableFloatStateOf(((now - windowStart) / 60 - 30).toFloat()) }
    // A State, not read here: only the offset lambdas read it (at layout time), so scrolling doesn't recompose the rows.
    val shown = animateFloatAsState(offsetMin, label = "guideScroll")
    val store = RelayApp.instance.store
    // Programmes with a reminder, as (channel, start). Equal sets keep the same instance, so rows aren't redrawn needlessly.
    val reminders by remember { derivedStateOf { store.version.intValue; store.reminders.map { it.channelId to it.start }.toSet() } }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    BoxWithConstraints(modifier) {
        val viewportMin = ((maxWidth - CHANNEL_COL).value / DP_PER_MIN).coerceAtLeast(60f)
        val maxOffset = (totalMin - viewportMin).coerceAtLeast(0f)
        val pxPerMin = with(density) { DP_PER_MIN.dp.toPx() }

        fun reveal(startMin: Float, endMin: Float) {
            offsetMin = when {
                endMin <= offsetMin + 5 -> (startMin - 10).coerceIn(0f, maxOffset)
                startMin >= offsetMin + viewportMin - 25 -> (startMin - 30).coerceIn(0f, maxOffset)
                else -> offsetMin
            }
        }

        Column(
            Modifier.fillMaxSize().pointerInput(maxOffset) {
                detectHorizontalDragGestures { _, dx -> offsetMin = (offsetMin - dx / pxPerMin).coerceIn(0f, maxOffset) }
            },
        ) {
            // Time ruler
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(now * 1000)),
                        color = Palette.dim, fontSize = Type.label, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    Box(
                        Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .width((totalMin * DP_PER_MIN).dp)
                            .fillMaxHeight()
                            .offset { IntOffset(-(shown.value * pxPerMin).roundToInt(), 0) },
                    ) {
                        var t = windowStart
                        val fmt = rulerFormat
                        while (t < windowEnd) {
                            val x = ((t - windowStart) / 60f * DP_PER_MIN).dp
                            Text(fmt.format(Date(t * 1000)), color = Palette.dim, fontSize = Type.label, modifier = Modifier.offset(x = x + 4.dp, y = 6.dp))
                            t += 1800
                        }
                        NowLine(((now - windowStart) / 60f * DP_PER_MIN).dp)
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().fadeEdges(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                    GuideRow(
                        ch = ch,
                        reload = reload,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        totalMin = totalMin,
                        shown = shown,
                        reminders = reminders,
                        pxPerMin = pxPerMin,
                        onReveal = ::reveal,
                        onWatch = { Playback.live(ctx, channels, i) },
                        onMessage = { message = it },
                    )
                }
            }
        }
        message?.let {
            Text(
                it,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Palette.surface2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = Palette.text, fontSize = Type.small,
            )
        }
    }
}

@Composable
private fun NowLine(x: androidx.compose.ui.unit.Dp) {
    Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(Palette.accent))
}

@Composable
private fun GuideRow(
    ch: Channel,
    reload: Int,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    totalMin: Float,
    shown: State<Float>,
    reminders: Set<Pair<Int, Long>>,
    pxPerMin: Float,
    onReveal: (Float, Float) -> Unit,
    onWatch: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val app = RelayApp.instance
    val ctx = LocalContext.current
    var entries by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
    var failed by remember(ch.id) { mutableStateOf(false) }
    var retry by remember(ch.id) { mutableIntStateOf(0) }
    LaunchedEffect(ch.id, reload, retry) {
        delay(120) // let fast scrolling skip rows before asking the provider
        val fresh = reload > 0 || retry > 0
        val got = app.catalog.gridEpgOrNull(ch, windowStart, windowEnd, fresh)
        // A failed reload keeps what's already on screen.
        if (got != null) entries = got
        failed = got == null && entries == null
    }
    val (tag, title) = remember(ch.name) { splitChannelName(ch.name) }
    // OK on the programme that is on now: channels with catch-up offer to start it from the beginning.
    var startOver by remember(ch.id) { mutableStateOf<EpgEntry?>(null) }
    startOver?.let { e ->
        val first = remember { FocusRequester() }
        InitialFocus(first)
        TvDialog({ startOver = null }) {
            Text(e.title, fontSize = Type.title, fontWeight = FontWeight.Bold, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("on $title", fontSize = Type.bodyLarge, color = Palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Watch live", { startOver = null; onWatch() }, focusRequester = first, primary = true, icon = Icons.Filled.PlayArrow, key = "so:live")
                PillButton("Start over", { startOver = null; Playback.catchup(ctx, ch, e) }, icon = Icons.Filled.Refresh, key = "so:restart")
            }
        }
    }

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Focusable(onWatch, Modifier.width(CHANNEL_COL - 6.dp).fillMaxHeight(), key = "gch:${ch.id}", shape = RoundedCornerShape(8.dp), scaleOnFocus = 1f) { f ->
            Row(
                Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 44.dp, height = 30.dp).background(Color(0xFFF2F5FA), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    LogoImage(ch.logo, Modifier.fillMaxSize().padding(2.dp)) {
                        Text(initials(title), color = Color(0xFF5B6B85), fontSize = Type.label, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, color = Palette.text, fontSize = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(ch.num.takeIf { it > 0 }?.toString(), tag).joinToString("  "),
                        color = Palette.dim, fontSize = Type.label, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Box(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width((totalMin * DP_PER_MIN).dp)
                    .fillMaxHeight()
                    .offset { IntOffset(-(shown.value * pxPerMin).roundToInt(), 0) },
            ) {
                val list = entries
                val nowX = ((now - windowStart) / 60f * DP_PER_MIN).dp
                when {
                    list == null && failed -> {
                        val s = ((now - windowStart) / 60f).coerceAtLeast(0f)
                        val w = 180f
                        Focusable({ failed = false; retry++ }, Modifier.offset(x = (s * DP_PER_MIN).dp).width((w * DP_PER_MIN).dp).fillMaxHeight(), key = "gfail:${ch.id}", shape = RoundedCornerShape(6.dp), scaleOnFocus = 1f, onFocused = { onReveal(s, s + w) }) { f ->
                            Box(Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                Text("Couldn't load guide · OK to retry", color = Palette.danger, fontSize = Type.caption, maxLines = 1)
                            }
                        }
                    }
                    list == null -> Text("Loading…", color = Palette.dim, fontSize = Type.label, modifier = Modifier.offset(x = nowX + 8.dp, y = 20.dp))
                    list.isEmpty() -> {
                        val s = ((now - windowStart) / 60f).coerceAtLeast(0f)
                        val w = 180f
                        Focusable(onWatch, Modifier.offset(x = (s * DP_PER_MIN).dp).width((w * DP_PER_MIN).dp).fillMaxHeight(), key = "gnone:${ch.id}", shape = RoundedCornerShape(6.dp), scaleOnFocus = 1f, onFocused = { onReveal(s, s + w) }) { f ->
                            Box(Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                Text("No guide data · OK to watch", color = Palette.dim, fontSize = Type.caption, maxLines = 1)
                            }
                        }
                    }
                    else -> list.forEach { e ->
                        val s = ((maxOf(e.start, windowStart) - windowStart) / 60f)
                        val en = ((minOf(e.end, windowEnd) - windowStart) / 60f)
                        if (en - s < 1f) return@forEach
                        val past = e.end <= now
                        val live = e.start <= now && now < e.end
                        val canCatchUp = past && ch.hasArchive && e.start >= now - ch.archiveDays * 86_400L
                        val reminder = !past && !live && (ch.id to e.start) in reminders
                        Focusable(
                            onClick = {
                                when {
                                    live -> { if (ch.hasArchive) startOver = e else onWatch() }
                                    past -> if (canCatchUp) Playback.catchup(ctx, ch, e) else onMessage("This show isn't available on catch-up")
                                    else -> onMessage(ReminderCenter.toggle(ch, e))
                                }
                            },
                            modifier = Modifier
                                .offset(x = (s * DP_PER_MIN).dp)
                                .width(((en - s) * DP_PER_MIN).dp - 3.dp)
                                .fillMaxHeight(),
                            key = "gp:${ch.id}:${e.start}",
                            shape = RoundedCornerShape(6.dp),
                            scaleOnFocus = 1f,
                            onFocused = { onReveal(s, en) },
                        ) { f ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        when {
                                            f -> Palette.accent
                                            live -> Palette.surface2
                                            past -> Palette.bg
                                            else -> Palette.surface
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                val fg = if (f) Color.Black else if (past && !canCatchUp) Palette.dim else Palette.text
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (reminder) {
                                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(3.dp))
                                    }
                                    if (canCatchUp) {
                                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(3.dp))
                                    }
                                    Text(
                                        e.title,
                                        color = fg, fontSize = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                                Text(
                                    "${hhmm(e.start)} – ${hhmm(e.end)}",
                                    color = if (f) Color.Black else Palette.dim, fontSize = Type.label, maxLines = 1,
                                )
                            }
                        }
                    }
                }
                NowLine(nowX)
            }
        }
    }
}

private val rulerFormat = SimpleDateFormat("h:mm", Locale.getDefault())
private val hm = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun hhmm(sec: Long): String = hm.format(Date(sec * 1000))
