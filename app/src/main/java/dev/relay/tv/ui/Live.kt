package dev.relay.tv.ui

import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.data.ChannelVersions
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.DeviceTier
import androidx.compose.foundation.layout.heightIn
import dev.relay.tv.data.LanguageDetector
import dev.relay.tv.data.Langs
import dev.relay.tv.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.relay.tv.data.ChannelGroup
import dev.relay.tv.data.LiveQuery
import dev.relay.tv.data.LiveSelection
import dev.relay.tv.data.Store
import dev.relay.tv.data.buildLiveList
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface CatRow {
    /** Unique in the list: a pinned category also appears again under its country. */
    val lazyKey: String

    data class Item(
        val id: String, val label: String, val count: Int?, val indent: Boolean = false, val pinned: Boolean = false,
        val icon: ImageVector? = null,
    ) : CatRow {
        override val lazyKey: String get() = (if (pinned) "pin:" else "lc:") + id
    }

    data class Header(val key: String, val label: String, val count: Int, val open: Boolean) : CatRow {
        override val lazyKey: String get() = "lh:$key"
    }
}

@Composable
fun LiveTab() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val store = app.store
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Both counters: the Recently watched list follows the history.
    val version = store.version.intValue + store.historyVersion.intValue
    val pid = app.session.profileId
    val restrict = app.session.restrict(store.settings)
    val relayKeys = useRelayKeyboard()

    // Reopen on the category used last time (once per profile session), and keep it saved.
    LaunchedEffect(Unit) {
        if (!ui.liveRestored) {
            ui.liveRestored = true
            // First visit: start on a short list (favourites, then recent) rather than every channel.
            ui.liveCat = store.lastLiveCat(pid) ?: when {
                store.favs(pid).any { it.kind == "live" } -> UiState.FAV
                store.history(pid).any { it.kind == "live" } -> UiState.HISTORY
                else -> ui.liveCat
            }
        }
    }
    LaunchedEffect(ui.liveCat) {
        store.setLastLiveCat(pid, ui.liveCat)
        AppLog.d("Live", "Category ${ui.liveCat}")
    }

    val groups = store.channelCustom.groups
    val pinned = store.pinnedCats
    val st = store.settings
    val rank = app.session.langRank(st)
    val hidden = st.hiddenCats
    val allCats = remember(catalog.liveCats, restrict) { catalog.liveCats.filter { !(restrict && it.adult) } }
    val allGroups = remember(allCats) { LiveBrowse.group(allCats) }
    // Categories hidden one by one or with their whole country (Settings → Manage categories).
    val hiddenIds: Set<String> = remember(allCats, allGroups, hidden) {
        buildSet {
            allCats.forEach { if ("live:${it.id}" in hidden) add(it.id) }
            allGroups?.forEach { g -> if ("livegroup:${g.key}" in hidden) g.cats.forEach { add(it.id) } }
        }
    }
    val providerCats = remember(allCats, hiddenIds) { allCats.filter { it.id !in hiddenIds } }
    // Countries and categories in your languages first; nothing else is removed.
    val countries = remember(allGroups, hiddenIds, rank, catalog.langVersion) {
        allGroups
            ?.filter { g -> "livegroup:${g.key}" !in hidden }
            ?.map { g -> g.copy(cats = g.cats.filter { it.id !in hiddenIds }.sortedBy { rank.rank(catalog.liveCatLang(it.id)) }) }
            ?.filter { it.cats.isNotEmpty() }
            ?.sortedBy { g -> rank.rank(LanguageDetector.ofCode(g.key) ?: LanguageDetector.ofCategory(g.label)) }
    }
    // Counted once when the library is indexed, not on every recomposition.
    val langCounts = remember(catalog.channels, catalog.langVersion) { catalog.channelLangCounts }
    val langOrder = app.session.langOrder(st)
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

    val total = catalog.channels.size
    val byCat = catalog.channelsByCat
    val rows: List<CatRow> = remember(providerCats, countries, shortName, openCountry, pinned, groups, langCounts, langOrder, byCat, total) {
      buildList {
        add(CatRow.Item(UiState.FAV, "Favourites", null, icon = Icons.Filled.Star))
        add(CatRow.Item(UiState.HISTORY, "Recently watched", null))
        pinned.forEach { id ->
            if (providerCats.any { it.id == id }) {
                val g = countries?.firstOrNull { gr -> gr.cats.any { it.id == id } }
                val prefix = if (g != null && g.key != "__other") "${g.label} · " else ""
                add(CatRow.Item(id, "$prefix${ProviderNames.clean(shortName[id] ?: id)}", byCat[id]?.size, pinned = true, icon = Icons.Filled.Place))
            }
        }
        groups.forEach { add(CatRow.Item("${ChannelEdits.GROUP}${it.id}", it.name, it.channelIds.size, icon = Icons.Filled.List)) }
        add(CatRow.Item(UiState.ALL, "All channels", total))
        // By language: Bangladesh sits right under Bengali.
        Langs.viewOrder(langOrder).forEach { k ->
            val n = langCounts[k] ?: 0
            if (n > 0) add(CatRow.Item("$LANG$k", Langs.label(k), n, icon = RelayIcons.Language))
        }
        if (countries == null) {
            providerCats.forEach { add(CatRow.Item(it.id, ProviderNames.clean(it.name), byCat[it.id]?.size)) }
        } else {
            countries.forEach { g ->
                val open = g.key == openCountry
                add(CatRow.Header(g.key, g.label, g.cats.size, open))
                if (open) g.cats.forEach { add(CatRow.Item(it.id, ProviderNames.clean(it.name), byCat[it.id]?.size, indent = true)) }
            }
        }
    }
    }

    val cat = ui.liveCat
    val sort = remember(cat, version) { store.liveSort(cat) }
    val favSet = remember(version, pid) { store.favs(pid).filter { it.kind == "live" }.map { it.id }.toSet() }
    val selection = remember(cat, version, pid, groups) { liveSelection(store, pid, cat, groups) }
    // Several versions of one channel (HD, FHD, backup) show once, unless you list them yourself.
    val merge = st.mergeVariants && cat != UiState.FAV && cat != UiState.HISTORY && !cat.startsWith(ChannelEdits.GROUP)
    // Typing in Find rebuilds the list once you pause, not after every letter.
    val find = rememberDebounced(ui.liveFind, DeviceTier.current.findPauseMs) { it.isEmpty() }
    val query = LiveQuery(
        cat = cat, selection = selection, sort = sort, filters = ui.liveFilters, find = find,
        blockedCats = if (restrict) catalog.adultCats else emptySet(), hiddenCats = hiddenIds,
        langOrder = langOrder, merge = merge,
        favIds = if (sort == 3 || selection is LiveSelection.Favourites) favSet.toList() else emptyList(),
        preferred = st.preferredVersions,
    )
    // The list is built off the screen thread. A new query cancels the one still running, and the
    // previous list stays up until the new one is ready.
    LaunchedEffect(query, catalog.channels, catalog.langVersion) {
        val t = SystemClock.elapsedRealtime()
        val built = withContext(Dispatchers.Default) { buildLiveList(catalog.liveData, query) { ensureActive() } }
        ui.liveResult = built
        val ms = SystemClock.elapsedRealtime() - t
        AppLog.d("Live", "Channel list for $cat: ${ms} ms, ${built.channels.size} shown")
        // Slow list building is what makes a TV feel frozen; log it when it happens.
        if (ms > 150) AppLog.w("Live", "Channel list for $cat took $ms ms off the screen thread (${built.baseSize} channels, ${built.channels.size} shown)")
    }
    val shown = ui.liveResult?.takeIf { it.query.cat == cat }
    val channels: List<Channel> = shown?.channels ?: emptyList()
    // Providers that don't number channels would leave an empty column; leave it out then.
    val showNumbers = remember(channels) { channels.any { it.num > 0 } }
    val focused = remember { mutableStateOf<Channel?>(null) }
    val isTv = rememberIsTv()
    val showPanel by remember { derivedStateOf { isTv || focused.value != null } }
    var finding by remember { mutableStateOf(false) }

    val isProviderCat = providerCats.any { it.id == ui.liveCat }
    val isPinned = ui.liveCat in pinned

    // The channel list keeps at least 45% of the width, whatever the screen: the category and
    // panel columns take shares of the real width instead of switching at one fixed size.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 900.dp
        val side = if (narrow) 16.dp else 32.dp
        val widths = LiveLayout.widths(maxWidth.value, side.value)
        val catWidth = widths.category.dp
        val panelWidth = widths.panel.dp
        Row(Modifier.fillMaxSize().padding(start = side, end = side, bottom = 16.dp)) {
            LazyColumn(
                Modifier.width(catWidth).fillMaxHeight().fadeEdges(),
                state = ui.liveCatList,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 20.dp),
            ) {
                items(rows, key = { it.lazyKey }) { row ->
                    when (row) {
                        is CatRow.Item -> ListRow(
                            if (row.indent) "   ${row.label}" else row.label,
                            onClick = {
                                ui.liveCat = row.id
                                ui.liveFind = ""
                                scope.launch { ui.liveChList.scrollToItem(0) }
                            },
                            selected = ui.liveCat == row.id,
                            key = row.lazyKey,
                            trailing = row.count?.toString(),
                            icon = row.icon,
                        )
                        is CatRow.Header -> ListRow(
                            row.label,
                            onClick = { ui.liveCountry = if (row.open) "" else row.key },
                            key = "lh:${row.key}",
                            trailing = row.count.toString(),
                            trailingIcon = if (row.open) Icons.Filled.ArrowDropDown else Icons.Filled.KeyboardArrowRight,
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
                // The letters come with the list (built off the screen thread), so a long list costs nothing here.
                val letters = shown?.letters.orEmpty()
                if (sort == 2 && letters.isNotEmpty() && !ui.liveGrid) {
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
                if (shown == null) {
                    EmptyState("Loading channels…", Modifier.weight(1f))
                } else if (channels.isEmpty()) {
                    EmptyState(
                        when {
                            shown.baseSize > 0 -> "No channels match these filters."
                            ui.liveCat == UiState.FAV -> "No favourite channels yet. Open a channel's panel and press Favourite."
                            ui.liveCat == UiState.HISTORY -> "Channels you watch will show up here."
                            catalog.loading -> "Loading… ${catalog.progress?.text.orEmpty()}"
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
                        itemsIndexed(channels, key = { _, c -> c.id }, contentType = { _, _ -> "channel" }) { i, ch ->
                            ChannelRow(
                                ch,
                                fav = ch.id in favSet,
                                showNumber = showNumbers,
                                onFocused = { focused.value = ch },
                                // Hold OK: add to or take off Favourites without opening the panel.
                                onLongClick = {
                                    val wasFav = ch.id in favSet
                                    store.toggleFav(pid, FavItem("live", ch.id, ch.name, ch.logo))
                                    Toast.makeText(
                                        ctx, if (wasFav) "Removed from Favourites" else "Added to Favourites", Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onClick = { Playback.live(ctx, channels, i) },
                            )
                        }
                    }
                }
            }
            // Phones show the panel only once a channel is selected; TVs keep it as a guide preview.
            if (!ui.liveGrid && showPanel) {
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

private fun liveSelection(store: Store, pid: String, cat: String, groups: List<ChannelGroup>): LiveSelection = when {
    cat == UiState.FAV -> LiveSelection.Favourites(store.favs(pid).filter { it.kind == "live" }.map { it.id })
    cat == UiState.HISTORY ->
        LiveSelection.History(store.history(pid).filter { it.kind == "live" }.mapNotNull { it.streamId.toIntOrNull() })
    cat == UiState.ALL -> LiveSelection.All
    cat.startsWith(LANG) -> LiveSelection.Language(cat.removePrefix(LANG))
    cat.startsWith(ChannelEdits.GROUP) ->
        LiveSelection.Group(groups.firstOrNull { "${ChannelEdits.GROUP}${it.id}" == cat }?.channelIds.orEmpty())
    else -> LiveSelection.Category(cat)
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
                // On a touch screen a chip is at least as tall and wide as a thumb needs; the label stays centred.
                .defaultMinSize(minWidth = minTouch, minHeight = minTouch)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 7.dp)
                .wrapContentSize(Alignment.Center),
            color = if (f) Color.Black else if (on) Palette.accent else Palette.text,
            fontSize = Type.caption, maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** System-keyboard version of a live-updating text prompt. */
@Composable
fun LiveTextDialog(title: String, value: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    InitialFocus(fr)
    TvDialog(onDismiss) {
        Text(title, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
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
private fun ChannelRow(ch: Channel, fav: Boolean, showNumber: Boolean, onFocused: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    val catalog = RelayApp.instance.catalog
    // Each row loads and shows its own programmes, so one arriving doesn't redraw the whole list.
    val guideRound = catalog.epgVersion
    var loaded by remember(ch.id) { mutableIntStateOf(0) }
    LaunchedEffect(ch.id, guideRound) {
        delay(DeviceTier.current.rowGuideDelayMs) // rows passed while scrolling quickly don't ask the provider
        catalog.prefetchNowNext(ch.id)
        loaded++
    }
    val nn = remember(ch.id, loaded, guideRound, catalog.minute) { catalog.nowNext(ch.id) }
    val name = remember(ch.name) { ProviderNames.clean(ch.name) }
    Focusable(
        onClick, Modifier.fillMaxWidth().height(66.dp), key = "ch:${ch.id}",
        shape = RoundedCornerShape(8.dp), scaleOnFocus = 1.02f, onFocused = onFocused, onLongClick = onLongClick,
    ) { f ->
        Row(
            Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumber) Text(if (ch.num > 0) ch.num.toString() else "", Modifier.width(52.dp), color = Palette.dim, fontSize = Type.small)
            Box(
                Modifier.size(width = 76.dp, height = 46.dp).background(Palette.bg, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                LogoImage(ch.logo, Modifier.fillMaxSize().padding(4.dp)) {
                    Text(initials(ch.name), color = Palette.dim, fontSize = Type.small, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Palette.text, fontSize = Type.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val cur = nn?.first
                val next = nn?.second
                when {
                    nn == null -> {}
                    cur == null && next == null -> Text("No guide available", color = Palette.dim, fontSize = Type.label)
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                cur?.title ?: "Nothing on now", Modifier.weight(1f, fill = false),
                                color = Palette.text.copy(alpha = 0.8f), fontSize = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (next != null) {
                                Text(
                                    "   Next: ${next.title}", color = Palette.dim, fontSize = Type.label,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (cur != null) {
                            val nowSec = System.currentTimeMillis() / 1000
                            val p = ((nowSec - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(Modifier.padding(top = 3.dp).fillMaxWidth(0.6f).height(2.dp).background(Palette.bg)) {
                                Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Palette.accent))
                            }
                        }
                    }
                }
            }
            if (ch.hasArchive) {
                Text("CATCH-UP", color = Palette.accent2, fontSize = Type.label, fontWeight = FontWeight.Bold)
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
private fun ChannelPanel(focus: State<Channel?>, modifier: Modifier, groupId: String?) {
    // Read here, not by the screen, so moving through the list only redraws the panel.
    val ch = focus.value
    Column(modifier.background(Palette.surface, RoundedCornerShape(12.dp)).padding(16.dp)) {
        if (ch == null) {
            Text("Move through the list to see what's on.", color = Palette.dim, fontSize = Type.body)
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
        val nowSec = System.currentTimeMillis() / 1000
        var epg by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
        var epgFailed by remember(ch.id) { mutableStateOf(false) }
        var epgRetry by remember(ch.id) { mutableIntStateOf(0) }
        var archive by remember(ch.id) { mutableStateOf<List<EpgEntry>?>(null) }
        var archiveFailed by remember(ch.id) { mutableStateOf(false) }
        var showArchive by remember(ch.id) { mutableStateOf(false) }
        var options by remember(ch.id) { mutableStateOf(false) }
        var versionsOpen by remember(ch.id) { mutableStateOf(false) }
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
                LogoImage(ch.logo, Modifier.fillMaxSize().padding(6.dp)) {}
            }
            Spacer(Modifier.width(14.dp))
            // The same cleaned name the list shows, not the provider's raw one.
            Text(ProviderNames.clean(ch.name), color = Palette.text, fontSize = Type.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        val fav = store.isFav(pid, "live", ch.id)
        val current = epg?.firstOrNull { it.start <= nowSec && nowSec < it.end }
        val versions = remember(ch.id, app.catalog.channels, store.settings.preferredVersions) { app.catalog.versionsOf(ch.id) }
        // Two buttons per row, so up to five of them leave room for the programme list on a 540 dp screen.
        val actions = buildList {
            add(PanelAction("Favourite", if (fav) Icons.Filled.Star else Icons.Filled.Add, "pf:${ch.id}") {
                store.toggleFav(pid, FavItem("live", ch.id, ch.name, ch.logo))
            })
            if (ch.hasArchive) {
                add(PanelAction(if (showArchive) "Guide" else "Catch-up", Icons.Filled.DateRange, "pa:${ch.id}") {
                    showArchive = !showArchive
                    if (archive == null) loadArchive()
                })
            }
            if (ch.hasArchive && current != null) {
                add(PanelAction("Start over", Icons.Filled.Refresh, "ps:${ch.id}") { Playback.catchup(ctx, ch, current) })
            }
            if (versions.size > 1) {
                add(PanelAction("Versions", Icons.Filled.List, "pv:${ch.id}") { versionsOpen = true })
            }
            add(PanelAction("Options", Icons.Filled.Settings, "po:${ch.id}") { options = true })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            actions.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { a ->
                        PillButton(a.text, a.onClick, Modifier.weight(1f), icon = a.icon, key = a.key, compact = true, fill = true)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (options) ChannelOptionsDialog(ch, groupId, onDismiss = { options = false })
        if (versionsOpen) ChannelVersionsDialog(ch, onDismiss = { versionsOpen = false })
        Spacer(Modifier.height(12.dp))

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
                                color = if (live) Palette.accent else Palette.dim, fontSize = Type.label,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(e.title, color = Palette.text, fontSize = Type.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (live && e.description.isNotBlank()) {
                                Text(e.description, color = Palette.dim, fontSize = Type.caption, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
                                    color = Palette.dim, fontSize = Type.label,
                                )
                                Text(e.title, color = Palette.text, fontSize = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PanelAction(val text: String, val icon: ImageVector, val key: String, val onClick: () -> Unit)

/** Every version of a channel: play one, or make it the one lists show and play first. */
@Composable
private fun ChannelVersionsDialog(ch: Channel, onDismiss: () -> Unit) {
    val app = RelayApp.instance
    val store = app.store
    store.version.intValue
    val ctx = LocalContext.current
    val versions = app.catalog.versionsOf(ch.id)
    val key = app.catalog.versionKeyOf(ch.id)
    val chosen = key?.let { store.settings.preferredVersions[it] }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    TvDialog(onDismiss, width = 620.dp) {
        Text("Versions of ${ProviderNames.clean(ch.name)}", fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
        Text(
            "Lists show the first one. If it fails to play, Relay tries the next. OK plays a version; " +
                "Use as main moves it to the top.",
            fontSize = Type.small, color = Palette.dim,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(versions.size) { i ->
                val v = versions[i]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListRow(
                        ProviderNames.clean(v.name),
                        onClick = { Playback.live(ctx, versions, i) },
                        modifier = Modifier.weight(1f),
                        key = "vr:${v.id}",
                        trailing = ChannelVersions.describe(v.name) + if (v.id == chosen) "  ·  Main" else "",
                        focusRequester = if (i == 0) first else null,
                    )
                    if (key != null && v.id != chosen) {
                        PillButton("Use as main", {
                            val s = store.settings
                            store.settings = s.copy(preferredVersions = s.preferredVersions + (key to v.id))
                            AppLog.i("Channels", "Main version of ${ProviderNames.clean(ch.name)}: ${v.name}")
                        }, key = "vm:${v.id}")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Done", onDismiss, primary = true)
            if (chosen != null && key != null) {
                PillButton("Best quality first", {
                    val s = store.settings
                    store.settings = s.copy(preferredVersions = s.preferredVersions - key)
                })
            }
        }
    }
}
