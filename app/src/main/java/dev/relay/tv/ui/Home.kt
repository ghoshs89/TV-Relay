package dev.relay.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.HomeLayout
import dev.relay.tv.data.Episode
import dev.relay.tv.data.WatchEntry
import dev.relay.tv.player.Playback
import dev.relay.tv.update.UpdateState
import dev.relay.tv.update.Updater
import kotlinx.coroutines.delay
import dev.relay.tv.data.AppLog
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TABS = listOf("Home", "Live TV", "Movies", "Series", "Search", "Settings")

@Composable
fun HomeScreen() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val tabFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { catalog.load() }
    InitialFocus(tabFocus)

    // While Relay is in front, refresh the library once it's 6 hours old (the external guide
    // looks after its own 12 hours). Nothing runs while the player is open on top.
    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    LaunchedEffect(Unit) {
        var minutes = 0
        while (true) {
            delay(60_000)
            catalog.tickMinute()
            if (++minutes % 30 != 0) continue
            val inFront = activity?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ?: true
            if (!inFront || catalog.loading) continue
            if (catalog.libraryDue()) {
                AppLog.i("Catalog", "Library is over 6 hours old; refreshing")
                catalog.load()
            } else {
                catalog.refreshGuideIfNeeded(force = false)
            }
        }
    }

    // A restore can remove or change the current profile: pick up the change, or go back to the picker.
    val nav = LocalNav.current

    // Offer library setup once (not on kids profiles). Then honour the chosen start screen.
    LaunchedEffect(Unit) {
        val st = app.store.settings
        if (!st.setupDone && app.session.profile?.kids != true) {
            nav.push(Route.Setup)
        } else if (!app.session.autoStartDone) {
            when (st.startScreen) {
                "live" -> app.ui.tab = 1
                "guide" -> {
                    app.ui.tab = 1
                    app.ui.liveGrid = true
                }
            }
        }
    }
    LaunchedEffect(app.store.version.intValue) {
        val current = app.session.profile ?: return@LaunchedEffect
        val now = app.store.profiles.firstOrNull { it.id == current.id }
        if (now == null) {
            app.session.profile = null
            app.ui = UiState()
            nav.reset(Route.Profiles)
        } else if (now != current) {
            app.session.profile = now
        }
    }

    // Poll GitHub for new builds while Relay is open; announce each new version once.
    LaunchedEffect(Unit) {
        while (true) {
            Updater.check(app.http, quiet = true)
            val s = Updater.state
            if (s is UpdateState.Available && s.info.versionCode != Updater.announcedCode) {
                Updater.announcedCode = s.info.versionCode
                Updater.dialogOpen = true
            }
            delay(30 * 60_000L)
        }
    }
    if (Updater.dialogOpen) UpdateDialog()

    // Expiry warning: only within 7 days of the end date, only for 5 s after the app starts.
    var expiryNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (app.session.expiryChecked) return@LaunchedEffect
        app.session.expiryChecked = true
        val exp = runCatching { app.api.authenticate() }.getOrNull()?.expires ?: return@LaunchedEffect
        val msLeft = exp * 1000 - System.currentTimeMillis()
        if (msLeft > 7 * 86_400_000L) return@LaunchedEffect
        val days = (msLeft / 86_400_000L).toInt()
        val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(exp * 1000))
        expiryNote = when {
            msLeft <= 0 -> "Your IPTV subscription has expired ($date)"
            days == 0 -> "Your IPTV subscription expires today ($date)"
            else -> "Your IPTV subscription expires in $days day${if (days == 1) "" else "s"} ($date)"
        }
        AppLog.i("App", expiryNote ?: "")
        delay(5_000)
        expiryNote = null
    }

    // Optional: go straight to the last live channel when the app starts.
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (app.session.autoStartDone) return@LaunchedEffect
        app.session.autoStartDone = true
        if (!app.store.settings.startOnLastChannel) return@LaunchedEffect
        val last = app.store.history(app.session.profileId).firstOrNull { it.kind == "live" } ?: return@LaunchedEffect
        val id = last.streamId.toIntOrNull() ?: return@LaunchedEffect
        withTimeoutOrNull(30_000) { snapshotFlow { catalog.channels.isNotEmpty() }.first { it } }
        val ch = catalog.channelById[id] ?: return@LaunchedEffect
        val list = catalog.channelsByCat[ch.categoryId].orEmpty().ifEmpty { listOf(ch) }
        AppLog.i("App", "Starting on last channel ${ch.name}")
        Playback.live(ctx, list, list.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Relay", color = Palette.accent, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(28.dp))
            TABS.forEachIndexed { i, t ->
                TabChip(
                    t, selected = ui.tab == i, key = "tab:$i",
                    focusRequester = if (i == ui.tab) tabFocus else null,
                ) { ui.tab = i }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (catalog.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            // A 1080p TV is only 960 dp wide, so the update badge takes the name and clock's place.
            if (Updater.pending != null) {
                PillButton("Update", { Updater.dialogOpen = true }, key = "tab:update", primary = true, icon = Icons.Filled.Refresh)
            } else {
                app.session.profile?.let {
                    Text(it.name, color = Palette.dim, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.width(16.dp))
                }
                Clock()
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.tab) {
                0 -> HomeTab()
                1 -> LiveTab()
                2 -> MoviesTab()
                3 -> SeriesTab()
                4 -> SearchTab()
                else -> SettingsTab()
            }
            if (catalog.isEmpty && catalog.loading && ui.tab != 5) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading your channels and library…", color = Palette.dim)
                    Text("Large playlists can take a minute the first time.", color = Palette.dim, fontSize = 13.sp)
                }
            }
            expiryNote?.let { note ->
                Text(
                    note,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color(0xF0B8462E), RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            catalog.error?.let { msg ->
                Text(
                    msg,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(Color(0xEE3A1D24), RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Palette.text, fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    text: String,
    selected: Boolean,
    key: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    Focusable(onClick, key = key, shape = RoundedCornerShape(50), focusRequester = focusRequester) { f ->
        Text(
            text,
            Modifier
                .background(if (f) Palette.accent else if (selected) Palette.surface2 else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (f) Color.Black else if (selected) Palette.text else Palette.dim,
            fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            now = fmt.format(Date())
            delay(15_000)
        }
    }
    Text(now, color = Palette.text, fontSize = 16.sp)
}

@Composable
fun HomeTab() {
    val app = RelayApp.instance
    val store = app.store
    val catalog = app.catalog
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    store.version.intValue
    val pid = app.session.profileId
    val restrict = app.session.restrict(store.settings)

    val history = store.history(pid)
    val continueWatching = history.filter { it.kind != "live" && it.inProgress }.take(20)
    val recentChannels = history.filter { it.kind == "live" }.mapNotNull { e -> e.streamId.toIntOrNull()?.let { catalog.channelById[it] } }.take(20)
    val favs = store.favs(pid)
    val favChannels = favs.filter { it.kind == "live" }.mapNotNull { catalog.channelById[it.id] }
    val myList = favs.filter { it.kind != "live" }

    val resume: (WatchEntry) -> Unit = { e ->
        val sid = e.seriesId
        if (e.kind == "episode" && sid != null) {
            scope.launch {
                val series = catalog.seriesById[sid]
                val info = runCatching { app.api.seriesInfo(sid) }.getOrNull()
                val all: List<Episode> = info?.let { inf -> inf.seasons.keys.sorted().flatMap { inf.seasons[it].orEmpty() } }.orEmpty()
                val idx = all.indexOfFirst { "ep:${it.id}" == e.key }
                if (series != null && idx >= 0) {
                    Playback.episodes(ctx, series, all, idx, e.position)
                } else {
                    Playback.resume(ctx, e)
                }
            }
        } else {
            Playback.resume(ctx, e)
        }
    }

    if (catalog.isEmpty && history.isEmpty() && !catalog.loading) {
        EmptyState("Nothing here yet. Check Settings if your playlist didn't load.")
        return
    }

    val st = store.settings
    val featured = app.session.langFeatured(st)
    val rows = HomeLayout.resolve(app.session.profile?.homeRows, featured)
    val hidden = st.hiddenCats

    LazyColumn(Modifier.fillMaxSize().fadeEdges(), state = app.ui.homeList, contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp)) {
        rows.forEach { row ->
            item(key = row) {
                when {
                    row == HomeLayout.CONTINUE -> Rail(HomeLayout.label(row), continueWatching) { e ->
                        PosterCard(
                            e.title, e.image, { resume(e) }, key = "cw:${e.key}", width = 260.dp, aspect = 16f / 9f,
                            subtitle = e.subtitle ?: "${fmtDuration(e.duration - e.position)} left",
                            progress = e.position.toFloat() / e.duration.coerceAtLeast(1),
                        )
                    }
                    row == HomeLayout.FAV_CHANNELS -> Rail(HomeLayout.label(row), favChannels) { ch ->
                        ChannelTile(
                            ch, { Playback.live(ctx, favChannels, favChannels.indexOf(ch)) },
                            key = "fc:${ch.id}", nowPlaying = catalog.nowPlaying(ch.id),
                        )
                    }
                    row == HomeLayout.RECENT_CHANNELS -> Rail(HomeLayout.label(row), recentChannels) { ch ->
                        ChannelTile(
                            ch, { Playback.live(ctx, recentChannels, recentChannels.indexOf(ch)) },
                            key = "rc:${ch.id}", nowPlaying = catalog.nowPlaying(ch.id),
                        )
                    }
                    row == HomeLayout.MY_LIST -> Rail(HomeLayout.label(row), myList) { f ->
                        PosterCard(f.name, f.image, {
                            if (f.kind == "movie") catalog.movieById[f.id]?.let { nav.push(Route.MovieDetail(it)) }
                            else catalog.seriesById[f.id]?.let { nav.push(Route.SeriesDetail(it)) }
                        }, key = "ml:${f.kind}:${f.id}")
                    }
                    row.startsWith(HomeLayout.LANG) -> {
                        val lang = row.removePrefix(HomeLayout.LANG)
                        val list = remember(lang, catalog.movies, catalog.series, restrict, catalog.langVersion, hidden) {
                            newIn(catalog, lang, restrict, hidden)
                        }
                        Rail(HomeLayout.label(row), list) { t -> TitleCard(t, "nl:$lang") }
                    }
                    row == HomeLayout.NEW_MOVIES -> {
                        val list = catalog.moviesByAdded.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "vod:${it.categoryId}" !in hidden }.take(30).toList()
                        Rail(HomeLayout.label(row), list) { m -> TitleCard(HomeTitle.M(m), "ram") }
                    }
                    row == HomeLayout.NEW_SERIES -> {
                        val list = catalog.seriesByAdded.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "series:${it.categoryId}" !in hidden }.take(30).toList()
                        Rail(HomeLayout.label(row), list) { x -> TitleCard(HomeTitle.S(x), "ras") }
                    }
                    row == HomeLayout.TOP_MOVIES -> {
                        val list = catalog.moviesByRating.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "vod:${it.categoryId}" !in hidden }.take(30).toList()
                        Rail(HomeLayout.label(row), list) { m ->
                            PosterCard(m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "trm:${m.id}", badge = "★ %.1f".format(m.rating))
                        }
                    }
                    row == HomeLayout.TOP_SERIES -> {
                        val list = catalog.seriesByRating.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "series:${it.categoryId}" !in hidden }.take(30).toList()
                        Rail(HomeLayout.label(row), list) { x ->
                            PosterCard(x.name, x.cover, { nav.push(Route.SeriesDetail(x)) }, key = "trs:${x.id}", badge = "★ %.1f".format(x.rating))
                        }
                    }
                }
            }
        }
    }
}

/** A movie or a series in a mixed row. */
sealed interface HomeTitle {
    val added: Long

    data class M(val movie: dev.relay.tv.data.Movie) : HomeTitle {
        override val added get() = movie.added
    }

    data class S(val series: dev.relay.tv.data.Series) : HomeTitle {
        override val added get() = series.added
    }
}

/** Newest movies and series in one language group, mixed by date. */
fun newIn(catalog: dev.relay.tv.data.Catalog, lang: String, restrict: Boolean, hidden: Set<String>): List<HomeTitle> {
    val m = catalog.moviesByAdded.asSequence()
        .filter { catalog.movieLang(it.id) == lang && catalog.allowed(it.categoryId, restrict) && "vod:${it.categoryId}" !in hidden }
        .take(30).map { HomeTitle.M(it) }
    val s = catalog.seriesByAdded.asSequence()
        .filter { catalog.seriesLang(it.id) == lang && catalog.allowed(it.categoryId, restrict) && "series:${it.categoryId}" !in hidden }
        .take(30).map { HomeTitle.S(it) }
    return (m + s).sortedByDescending { it.added }.take(30).toList()
}

@Composable
private fun TitleCard(t: HomeTitle, keyPrefix: String) {
    val nav = LocalNav.current
    when (t) {
        is HomeTitle.M -> {
            val m = t.movie
            val rt = posterScore("m", m.id)
            PosterCard(
                m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "$keyPrefix:m:${m.id}", subtitle = m.year,
                badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
            )
        }
        is HomeTitle.S -> {
            val x = t.series
            val rt = posterScore("s", x.id)
            PosterCard(
                x.name, x.cover, { nav.push(Route.SeriesDetail(x)) }, key = "$keyPrefix:s:${x.id}", subtitle = x.year,
                badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
            )
        }
    }
}
