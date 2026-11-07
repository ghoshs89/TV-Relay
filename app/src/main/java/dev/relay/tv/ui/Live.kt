package dev.relay.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.mutableIntStateOf
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

private sealed interface CatRow {
    data class Item(val id: String, val label: String, val count: Int?, val indent: Boolean = false) : CatRow
    data class Header(val key: String, val label: String, val count: Int, val open: Boolean) : CatRow
}

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
    val relayKeys = useRelayKeyboard()

    // Reopen on the category used last time (once per profile session), and keep it saved.
    LaunchedEffect(Unit) {
        if (!ui.liveRestored) {
            ui.liveRestored = true
            store.lastLiveCat(pid)?.let { ui.liveCat = it }
        }
    }
    LaunchedEffect(ui.liveCat) { store.setLastLiveCat(pid, ui.liveCat) }

    val groups = store.channelCustom.groups
    val pinned = store.pinnedCats
    val providerCats = remember(catalog.liveCats, restrict) { catalog.liveCats.filter { !(restrict && it.adult) } }
    val countries = remember(providerCats) { LiveBrowse.group(providerCats) }
    val shortName: Map<String, String> = remember(providerCats, countries) {
        countries?.flatMap { g -> g.cats.map { it.id to it.name } }?.toMap() ?: providerCats.associate { it.id to it.name }
    }
    val selectedCountry = countries?.firstOrNull { g -> g.cats.any { it.id == ui.liveCat } }?.key
    // null = follow the selected category; "" = all collapsed; otherwise the open country.
    val openCountry = when (val c = ui.liveCountry) {
        null -> selectedCountry
        "" -> null
        else -> c
    }

    val rows: List<CatRow> = buildList {
        add(CatRow.Item(UiState.FAV, "★ Favourites", null))
        add(CatRow.Item(UiState.HISTORY, "Recently watched", null))
        pinned.forEach { id ->
            if (providerCats.any { it.id == id }) {
                val g = countries?.firstOrNull { gr -> gr.cats.any { it.id == id } }
                val prefix = if (g != null && g.key != "__other") "${g.label} · " else ""
                add(CatRow.Item(id, "📌 $prefix${shortName[id] ?: id}", catalog.channelsByCat[id]?.size))
            }
        }
        groups.forEach { add(CatRow.Item("${ChannelEdits.GROUP}${it.id}", "▸ ${it.name}", it.channelIds.size)) }
        add(CatRow.Item(UiState.ALL, "All channels", catalog.channels.size))
        if (countries == null) {
            providerCats.forEach { add(CatRow.Item(it.id, it.name, catalog.channelsByCat[it.id]?.size)) }
        } else {
            countries.forEach { g ->
                val open = g.key == openCountry
                add(CatRow.Header(g.key, g.label, g.cats.size, open))
                if (open) g.cats.forEach { add(CatRow.Item(it.id, it.name, catalog.channelsByCat[it.id]?.size, indent = true)) }
            }
        }
    }

    val base: List<Channel> = remember(ui.liveCat, catalog.channels, restrict, version) {
        when (ui.liveCat) {
            UiState.FAV -> store.favs(pid).filter { it.kind == "live" }.mapNotNull { catalog.channelById[it.id] }
            UiState.HISTORY -> store.history(pid).filter { it.kind == "live" }
                .mapNotNull { e -> e.streamId.toIntOrNull()?.let { catalog.channelById[it] } }
            UiState.ALL -> if (restrict) catalog.channels.filter { catalog.allowed(it.categoryId, true) } else catalog.channels
            else -> if (ui.liveCat.startsWith(ChannelEdits.GROUP)) {
                groups.firstOrNull { "${ChannelEdits.GROUP}${it.id}" == ui.liveCat }
                    ?.let { catalog.groupChannels(it) }.orEmpty()
                    .filter { catalog.allowed(it.categoryId, restrict) }
            } else {
                catalog.channelsByCat[ui.liveCat].orEmpty()
            }
        }
    }
    val sort = remember(ui.liveCat, version) { store.liveSort(ui.liveCat) }
    val favIds = remember(version, pid) { store.favs(pid).filter { it.kind == "live" }.map { it.id }.toSet() }
    val channels = remember(base, sort, ui.liveFilters, ui.liveFind, favIds) {
        LiveBrowse.apply(base, sort, ui.liveFilters, ui.liveFind, favIds)
    }
    var focused by remember { mutableStateOf<Channel?>(null) }
    var finding by remember { mutableStateOf(false) }
    catalog.epgVersion

    val isProviderCat = providerCats.any { it.id == ui.liveCat }
    val isPinned = ui.liveCat in pinned

    // 1080p TVs are 960 dp wide and phones in landscape ~750 dp: size columns to fit the channel list.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 900.dp
        val catWidth = if (narrow) 170.dp else 220.dp
        val panelWidth = if (narrow) 240.dp else 300.dp
        Row(Modifier.fillMaxSize().padding(start = if (narrow) 16.dp else 32.dp, end = if (narrow) 16.dp else 32.dp, bottom = 16.dp)) {
            LazyColumn(
                Modifier.width(catWidth).fillMaxHeight().fadeEdges(),
                state = ui.liveCatList,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 20.dp),
            ) {
                items(rows) { row ->
                    when (row) {
                        is CatRow.Item -> ListRow(
                            if (row.indent) "   ${row.label}" else row.label,
                            onClick = {
                                ui.liveCat = row.id
                                ui.liveFind = ""
                                scope.launch { ui.liveChList.scrollToItem(0) }
                            },
                            selected = ui.liveCat == row.id,
                            key = "lc:${row.id}",
                            trailing = row.count?.toString(),
                        )
                        is CatRow.Header -> ListRow(
                            row.label,
                            onClick = { ui.liveCountry = if (row.open) "" else row.key },
                            key = "lh:${row.key}",
                            trailing = "${row.count}  ${if (row.open) "▾" else "▸"}",
                            textColor = Palette.accent2,
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyRow(
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Chip(if (ui.liveGrid) "List view" else "Guide grid", false, "tb:grid") { ui.liveGrid = !ui.liveGrid }
                    }
                    item {
                        Chip("Sort: ${LiveBrowse.SORTS[sort.coerceIn(0, 3)]}", false, "tb:sort") {
                            store.setLiveSort(ui.liveCat, (sort + 1) % LiveBrowse.SORTS.size)
                            scope.launch { ui.liveChList.scrollToItem(0) }
                        }
                    }
                    item {
                        Chip(if (ui.liveFind.isBlank()) "Find" else "Find: ${ui.liveFind}", ui.liveFind.isNotBlank(), "tb:find") { finding = true }
                    }
                    if (ui.liveFind.isNotBlank()) item { Chip("Clear find", false, "tb:clear") { ui.liveFind = "" } }
                    item { FilterChip("Catch-up", LiveBrowse.F_CATCHUP) }
                    item { FilterChip("HD / 4K", LiveBrowse.F_HD) }
                    item { FilterChip("Has guide", LiveBrowse.F_GUIDE) }
                    if (isProviderCat) {
                        item {
                            Chip(if (isPinned) "Unpin category" else "Pin category", isPinned, "tb:pin") {
                                store.pinnedCats = if (isPinned) pinned - ui.liveCat else pinned + ui.liveCat
                            }
                        }
                    }
                }
                if (sort == 2 && channels.size > 40 && !ui.liveGrid) {
                    val letters = remember(channels) {
                        channels.mapIndexedNotNull { i, ch ->
                            val c = LiveBrowse.sortKey(ch).firstOrNull()?.uppercaseChar() ?: return@mapIndexedNotNull null
                            (if (c.isLetter()) c.toString() else "#") to i
                        }.distinctBy { it.first }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(letters) { (letter, index) ->
                            Chip(letter, false, "az:$letter", compact = true) {
                                scope.launch { ui.liveChList.scrollToItem(index) }
                            }
                        }
                    }
                }
                if (channels.isEmpty()) {
                    EmptyState(
                        when {
                            base.isNotEmpty() -> "No channels match these filters."
                            ui.liveCat == UiState.FAV -> "No favourite channels yet. Open a channel's panel and press Favourite."
                            ui.liveCat == UiState.HISTORY -> "Channels you watch will show up here."
                            catalog.loading -> "Loading…"
                            else -> "No channels in this category."
                        },
                        Modifier.weight(1f),
                    )
                } else if (ui.liveGrid) {
                    GuideGrid(channels, Modifier.weight(1f).fillMaxWidth())
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().fadeEdges(),
                        state = ui.liveChList,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
                    ) {
                        itemsIndexed(channels) { i, ch ->
                            ChannelRow(
                                ch,
                                now = catalog.nowPlaying(ch.id),
                                fav = ch.id in favIds,
                                onFocused = { focused = ch },
                                onClick = { Playback.live(ctx, channels, i) },
                            )
                        }
                    }
                }
            }
            if (!ui.liveGrid) {
                Spacer(Modifier.width(14.dp))
                ChannelPanel(
                    focused,
                    Modifier.width(panelWidth).fillMaxHeight(),
                    groupId = ui.liveCat.takeIf { it.startsWith(ChannelEdits.GROUP) }?.removePrefix(ChannelEdits.GROUP),
                )
            }
        }
    }

    if (finding) {
        if (relayKeys) {
            KeyboardDialog(
                label = "Find in this list",
                value = ui.liveFind,
                onValueChange = { ui.liveFind = it },
                password = false,
                onDone = { finding = false },
                onDismiss = { finding = false },
            )
        } else {
            LiveTextDialog("Find in this list", ui.liveFind, { ui.liveFind = it }, onDismiss = { finding = false })
        }
    }
}

@Composable
private fun FilterChip(label: String, filter: String) {
    val ui = RelayApp.instance.ui
    val on = filter in ui.liveFilters
    Chip(label, on, "tb:$filter") { ui.liveFilters = if (on) ui.liveFilters - filter else ui.liveFilters + filter }
}

@Composable
private fun Chip(label: String, on: Boolean, key: String, compact: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick, key = key, shape = RoundedCornerShape(50), scaleOnFocus = 1.06f) { f ->
        Text(
            if (on && !compact) "✓ $label" else label,
            Modifier
                .background(if (f) Palette.accent else if (on) Palette.surface2 else Palette.surface)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 7.dp),
            color = if (f) Color.Black else if (on) Palette.accent else Palette.text,
            fontSize = 13.sp, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** System-keyboard version of a live-updating text prompt. */
@Composable
fun LiveTextDialog(title: String, value: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    TvDialog(onDismiss) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        TvTextField(
            value, onValueChange, title, Modifier.fillMaxWidth(),
            focusRequester = fr, autoOpen = true, imeAction = ImeAction.Done, onDone = onDismiss,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Done", onDismiss, primary = true)
            PillButton("Clear", { onValueChange("") })
        }
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
private fun ChannelPanel(ch: Channel?, modifier: Modifier, groupId: String?) {
    Column(modifier.background(Palette.surface, RoundedCornerShape(12.dp)).padding(20.dp)) {
        if (ch == null) {
            Text("Move through the list to see what's on.", color = Palette.dim, fontSize = 15.sp)
        } else {
            ChannelDetails(ch, groupId)
        }
    }
}

@Composable
private fun ChannelDetails(ch: Channel, groupId: String?) {
    val app = RelayApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = app.store
    store.version.intValue
    val pid = app.session.profileId
    run {
        var epg by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
        var epgFailed by remember(ch.id) { mutableStateOf(false) }
        var epgRetry by remember(ch.id) { mutableIntStateOf(0) }
        var archive by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
        var archiveFailed by remember(ch.id) { mutableStateOf(false) }
        var showArchive by remember(ch.id) { mutableStateOf(false) }
        var options by remember(ch.id) { mutableStateOf(false) }
        LaunchedEffect(ch.id, epgRetry) {
            delay(250)
            epgFailed = false
            val got = app.catalog.shortEpgOrNull(ch.id, fresh = epgRetry > 0)
            epg = got
            epgFailed = got == null
        }
        val loadArchive: () -> Unit = {
            archiveFailed = false
            scope.launch {
                val got = app.catalog.archiveOrNull(ch.id)
                archive = got
                archiveFailed = got == null
            }
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
        // Stacked so both buttons fit the narrower panel on 1080p TVs and phones.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (fav) "★ Favourite" else "☆ Favourite", {
                store.toggleFav(pid, FavItem("live", ch.id, ch.name, ch.logo))
            }, key = "pf:${ch.id}")
            if (ch.hasArchive) {
                PillButton(if (showArchive) "Guide" else "Catch-up", {
                    showArchive = !showArchive
                    if (archive == null) loadArchive()
                }, key = "pa:${ch.id}")
            }
            PillButton("Options", { options = true }, key = "po:${ch.id}")
        }
        if (options) ChannelOptionsDialog(ch, groupId, onDismiss = { options = false })
        Spacer(Modifier.height(16.dp))

        val nowSec = System.currentTimeMillis() / 1000
        if (!showArchive) {
            val list = epg
            when {
                list == null && epgFailed -> LoadFailed("Couldn't load the guide.", key = "pr:${ch.id}") { epgRetry++ }
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
                past == null && archiveFailed -> LoadFailed("Couldn't load catch-up.", key = "prc:${ch.id}") { loadArchive() }
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
