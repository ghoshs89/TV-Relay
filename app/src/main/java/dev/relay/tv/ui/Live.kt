package dev.relay.tv.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Category
import dev.relay.tv.data.Channel
import dev.relay.tv.data.EpgEntry
import dev.relay.tv.data.FavItem
import dev.relay.tv.player.Playback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveTab() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val store = app.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = store.version.intValue
    val pid = app.session.profileId
    val restrict = app.session.restrict(store.settings)

    val cats = remember(catalog.liveCats, restrict) {
        listOf(
            Category(UiState.FAV, "★ Favourites"),
            Category(UiState.HISTORY, "Recently watched"),
            Category(UiState.ALL, "All channels"),
        ) + catalog.liveCats.filter { !(restrict && it.adult) }
    }
    val channels: List<Channel> = remember(ui.liveCat, catalog.channels, restrict, version) {
        when (ui.liveCat) {
            UiState.FAV -> store.favs(pid).filter { it.kind == "live" }.mapNotNull { catalog.channelById[it.id] }
            UiState.HISTORY -> store.history(pid).filter { it.kind == "live" }
                .mapNotNull { e -> e.streamId.toIntOrNull()?.let { catalog.channelById[it] } }
            UiState.ALL -> if (restrict) catalog.channels.filter { catalog.allowed(it.categoryId, true) } else catalog.channels
            else -> catalog.channelsByCat[ui.liveCat].orEmpty()
        }
    }
    var focused by remember { mutableStateOf<Channel?>(null) }
    catalog.epgVersion

    Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 16.dp)) {
        LazyColumn(
            Modifier.width(250.dp).fillMaxHeight(),
            state = ui.liveCatList,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(cats) { c ->
                val count = when (c.id) {
                    UiState.FAV, UiState.HISTORY -> null
                    UiState.ALL -> catalog.channels.size
                    else -> catalog.channelsByCat[c.id]?.size
                }
                ListRow(
                    c.name,
                    onClick = {
                        ui.liveCat = c.id
                        scope.launch { ui.liveChList.scrollToItem(0) }
                    },
                    selected = ui.liveCat == c.id,
                    key = "lc:${c.id}",
                    trailing = count?.toString(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        if (channels.isEmpty()) {
            EmptyState(
                when (ui.liveCat) {
                    UiState.FAV -> "No favourite channels yet. Open a channel's panel and press Favourite."
                    UiState.HISTORY -> "Channels you watch will show up here."
                    else -> if (catalog.loading) "Loading…" else "No channels in this category."
                },
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                state = ui.liveChList,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
            ) {
                itemsIndexed(channels) { i, ch ->
                    ChannelRow(
                        ch,
                        now = catalog.nowPlaying(ch.id),
                        fav = store.isFav(pid, "live", ch.id),
                        onFocused = { focused = ch },
                        onClick = { Playback.live(ctx, channels, i) },
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        ChannelPanel(focused, Modifier.width(400.dp).fillMaxHeight())
    }
}

@Composable
private fun ChannelRow(ch: Channel, now: String?, fav: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    Focusable(
        onClick, Modifier.fillMaxWidth().height(66.dp), key = "ch:${ch.id}",
        shape = RoundedCornerShape(8.dp), scaleOnFocus = 1.02f, onFocused = onFocused,
    ) { f ->
        Row(
            Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (ch.num > 0) ch.num.toString() else "", Modifier.width(52.dp), color = Palette.dim, fontSize = 14.sp)
            Box(
                Modifier.size(width = 76.dp, height = 46.dp).background(Palette.bg, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (!ch.logo.isNullOrBlank()) {
                    AsyncImage(ch.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                } else {
                    Text(initials(ch.name), color = Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = Palette.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) Text(now, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (ch.hasArchive) {
                Text("CATCH-UP", color = Palette.accent2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            if (fav) Icon(Icons.Filled.Star, null, tint = Palette.accent, modifier = Modifier.size(18.dp))
        }
    }
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private fun hm(sec: Long) = timeFmt.format(Date(sec * 1000))

@Composable
private fun ChannelPanel(ch: Channel?, modifier: Modifier) {
    Column(modifier.background(Palette.surface, RoundedCornerShape(12.dp)).padding(20.dp)) {
        if (ch == null) {
            Text("Move through the list to see what's on.", color = Palette.dim, fontSize = 15.sp)
        } else {
            ChannelDetails(ch)
        }
    }
}

@Composable
private fun ChannelDetails(ch: Channel) {
    val app = RelayApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = app.store
    store.version.intValue
    val pid = app.session.profileId
    run {
        var epg by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
        var archive by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
        var showArchive by remember(ch.id) { mutableStateOf(false) }
        LaunchedEffect(ch.id) {
            delay(250)
            epg = app.catalog.shortEpg(ch.id)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 96.dp, height = 58.dp).background(Palette.bg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (!ch.logo.isNullOrBlank()) {
                    AsyncImage(ch.logo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(ch.name, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
        Spacer(Modifier.height(16.dp))
        val fav = store.isFav(pid, "live", ch.id)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (fav) "★ Favourite" else "☆ Favourite", {
                store.toggleFav(pid, FavItem("live", ch.id, ch.name, ch.logo))
            }, key = "pf:${ch.id}")
            if (ch.hasArchive) {
                PillButton(if (showArchive) "Guide" else "Catch-up", {
                    showArchive = !showArchive
                    if (archive == null) scope.launch { archive = app.catalog.archive(ch.id) }
                }, key = "pa:${ch.id}")
            }
        }
        Spacer(Modifier.height(16.dp))

        val nowSec = System.currentTimeMillis() / 1000
        if (!showArchive) {
            val list = epg
            when {
                list == null -> Text("Loading guide…", color = Palette.dim)
                list.none { it.end > nowSec } -> Text("No guide data for this channel.", color = Palette.dim)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list.filter { it.end > nowSec }) { e ->
                        val live = e.start <= nowSec
                        Column {
                            Text(
                                (if (live) "NOW  " else "") + "${hm(e.start)} – ${hm(e.end)}",
                                color = if (live) Palette.accent else Palette.dim, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(e.title, color = Palette.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (live && e.description.isNotBlank()) {
                                Text(e.description, color = Palette.dim, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            val list = archive
            val oldest = nowSec - ch.archiveDays * 86_400L
            val past = list?.filter { it.end <= nowSec && (it.hasArchive || it.start >= oldest) }
                ?.sortedByDescending { it.start }
            when {
                past == null -> Text("Loading catch-up…", color = Palette.dim)
                past.isEmpty() -> Text("No recorded programmes available.", color = Palette.dim)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(past) { e ->
                        Focusable(
                            { Playback.catchup(ctx, ch, e) }, Modifier.fillMaxWidth(),
                            key = "cu:${ch.id}:${e.start}", shape = RoundedCornerShape(8.dp), scaleOnFocus = 1.02f,
                        ) { f ->
                            Column(Modifier.fillMaxWidth().background(if (f) Palette.surface2 else Palette.bg).padding(10.dp)) {
                                Text(
                                    "${dayFmt.format(Date(e.start * 1000))}  ${hm(e.start)}",
                                    color = Palette.dim, fontSize = 12.sp,
                                )
                                Text(e.title, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
