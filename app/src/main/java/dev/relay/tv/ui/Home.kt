package dev.relay.tv.ui

import android.widget.Toast
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
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import dev.relay.tv.tv.WatchNext
import dev.relay.tv.data.HistoryEdits
import dev.relay.tv.data.ProviderNames


@Composable
fun HomeScreen() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val nav = LocalNav.current

    // In the app's scope: opening another screen mustn't cancel a load halfway.
    LaunchedEffect(Unit) { app.appScope.launch { catalog.load() } }
    LaunchedEffect(ui.tab) { AppLog.d("Nav", "Tab: ${Dest.active(ui).label}") }

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
                app.appScope.launch { catalog.load() }
            } else {
                catalog.refreshGuideIfNeeded(force = false)
            }
        }
    }

    // A restore can remove or change the current profile: pick up the change, or go back to the picker.

    // "Continue watching" on the TV home screen: resume that title if it belongs to this profile.
    val resumeCtx = LocalContext.current
    LaunchedEffect(app.pendingResume, app.session.profileId) {
        val (p, k) = app.pendingResume ?: return@LaunchedEffect
        app.pendingResume = null
        if (p != app.session.profileId) {
            AppLog.i("WatchNext", "Home screen link is for another profile; not resuming")
            return@LaunchedEffect
        }
        val e = app.store.entry(p, k) ?: return@LaunchedEffect
        AppLog.i("WatchNext", "Resuming from the home screen: ${e.title}")
        Playback.resume(resumeCtx, e)
    }

    // Offer library setup once (not on kids profiles). Then honour the chosen start screen.
    LaunchedEffect(Unit) {
        val st = app.store.settings
        if (!st.setupDone && app.session.profile?.kids != true) {
            nav.push(Route.Setup)
        } else if (!app.session.autoStartDone) {
            when (st.startScreen) {
                "live" -> Dest.LIVE.open(app.ui)
                "guide" -> Dest.GUIDE.open(app.ui)
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
            Updater.check(app.http, quiet = true, includePrereleases = app.store.settings.includePrereleases)
            val s = Updater.state
            if (s is UpdateState.Available && s.info.versionCode != Updater.announcedCode) {
                Updater.announcedCode = s.info.versionCode
                Updater.dialogOpen = true
            }
            delay(30 * 60_000L)
        }
    }
    if (Updater.dialogOpen) UpdateDialog()

    var crashPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(app.session.profileId) {
        if (!crashPrompt && app.telemetry.configured && app.session.profile?.kids != true) {
            val crashAt = AppLog.lastCrash()
            if (crashAt != null && crashAt > app.telemetry.lastPromptedCrashAt) {
                crashPrompt = true
            }
        }
    }
    if (crashPrompt) CrashReportDialog(onDismiss = { crashPrompt = false })

    // Expiry warning: only within 7 days of the end date, only for 5 s after the app starts.
    var expiryNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (app.session.expiryChecked) return@LaunchedEffect
        val info = try {
            app.api.authenticate()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        app.session.expiryChecked = true
        val exp = info.expires ?: return@LaunchedEffect
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

    // Optional: go straight to the last live channel when the app starts (unless opened from a TV Home resume card).
    val ctx = LocalContext.current
    var resumingDeepLink by remember { mutableStateOf(app.pendingResume != null) }
    LaunchedEffect(app.pendingResume) {
        if (app.pendingResume != null) resumingDeepLink = true
    }
    LaunchedEffect(Unit) {
        if (app.session.autoStartDone) return@LaunchedEffect
        app.session.autoStartDone = true
        if (resumingDeepLink || app.pendingResume != null) return@LaunchedEffect
        if (!app.store.settings.startOnLastChannel) return@LaunchedEffect
        val last = app.store.history(app.session.profileId).firstOrNull { it.kind == "live" } ?: return@LaunchedEffect
        val id = last.streamId.toIntOrNull() ?: return@LaunchedEffect
        withTimeoutOrNull(30_000) { snapshotFlow { catalog.channels.isNotEmpty() }.first { it } }
        if (resumingDeepLink || app.pendingResume != null) return@LaunchedEffect
        val ch = catalog.channelById[id] ?: return@LaunchedEffect
        // The same limits as browsing: a restricted profile can't reach a category this way that it can't open.
        val access = catalog.restrictions(app.session.restrict(app.store.settings))
        if (!access.allowsCategory(ch.categoryId)) return@LaunchedEffect
        val list = catalog.channelsByCat[ch.categoryId].orEmpty().ifEmpty { listOf(ch) }
        AppLog.i("App", "Starting on last channel ${ch.name}")
        Playback.live(ctx, list, list.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }

    // Back: an open panel or dialog closes first (their own handlers come later in composition). Then,
    // on a TV, focus moves from the content to the sidebar; from the sidebar (or on a phone) Back goes to
    // Home, and from Home it asks Close Relay? (MainActivity).
    val tv = rememberIsTv()
    val requesters = remember { Dest.entries.associateWith { FocusRequester() } }
    val profileRequester = remember { FocusRequester() }
    var sidebarFocused by remember { mutableStateOf(false) }
    val gate = rememberParentGate()
    val switchProfile: () -> Unit = {
        val leave = {
            val approval = app.session.profileSwitchLock(app.store)
            app.session.profile = null
            app.ui = UiState()
            nav.openProfilePicker(approval)
        }
        if (app.session.profileSwitchLock(app.store) != null) gate.run("Parent PIN to leave the kids profile", leave) else leave()
    }
    val onTop = LocalFocusEnabled.current
    val activeDest = Dest.active(ui)
    androidx.activity.compose.BackHandler(enabled = onTop && ((tv && !sidebarFocused) || activeDest != Dest.HOME)) {
        if (tv && !sidebarFocused) {
            runCatching { requesters.getValue(activeDest).requestFocus() }
        } else {
            Dest.HOME.open(ui)
            if (tv) runCatching { requesters.getValue(Dest.HOME).requestFocus() }
        }
    }
    // On a TV the sidebar's current item gets focus first; Right goes into the content.
    if (tv) InitialFocus(requesters.getValue(activeDest))

    NavFrame(
        tv = tv,
        sidebar = { TvSidebar(requesters, profileRequester, { sidebarFocused = it }, switchProfile) },
        bar = { PhoneBar(switchProfile) },
    ) {
        Column(Modifier.fillMaxSize()) {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.tab) {
                Tabs.HOME -> HomeTab()
                Tabs.LIVE -> Padded { LiveTab() }
                Tabs.MOVIES -> Padded { MoviesTab() }
                Tabs.SERIES -> Padded { SeriesTab() }
                Tabs.SEARCH -> Padded { SearchTab() }
                Tabs.MY_LIST -> Padded { MyListTab() }
                else -> Padded { SettingsTab() }
            }
            if (catalog.isEmpty && catalog.loading && ui.tab != Tabs.SETTINGS) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading your channels and library…", color = Palette.dim)
                    catalog.progress?.let { Text(it.text, color = Palette.text, fontSize = 18.sp) }
                    Text("Large playlists can take a few minutes on a TV the first time.", color = Palette.dim, fontSize = Type.caption)
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
                    color = Color.White, fontSize = Type.bodyLarge, fontWeight = FontWeight.SemiBold,
                )
            }
          }
          // Below the content (not on top of it) so D-pad down from the last row reaches the buttons.
          catalog.error?.let { msg ->
              ErrorBar(
                  msg,
                  onRetry = { app.appScope.launch { catalog.load(force = true) } },
                  onDismiss = { catalog.error = null },
              )
          }
        }
    }
}


/** Every tab but Home starts a little below the top edge, where the old tab bar was. */
@Composable
private fun Padded(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = 14.dp)) { content() }
}

/** What a long press on a Home card offers: [action] is the title, [title] the item, [run] does it. */
private class Removal(val action: String, val title: String, val confirm: String = "Remove", val run: () -> Unit)

/** A load problem you can act on: try again, or put the message away. */
@Composable
private fun ErrorBar(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .background(Color(0xEE3A1D24), RoundedCornerShape(10.dp))
            .padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f), color = Palette.text, fontSize = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        PillButton("Retry", onRetry, key = "err:retry", icon = Icons.Filled.Refresh, compact = true)
        Spacer(Modifier.width(8.dp))
        PillButton("Dismiss", onDismiss, key = "err:dismiss", compact = true)
    }
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
    store.historyVersion.intValue
    val pid = app.session.profileId
    val restrict = app.session.restrict(store.settings)
    val restrictions = remember(restrict, catalog.adultCats, catalog.libraryVersion) { catalog.restrictions(restrict) }

    // The store hands back the same list until something is written, so these are worked out
    // again only when the history, favourites or channels really change.
    val history = store.history(pid)
    val continueWatching = remember(history, restrict, catalog.adultCats, catalog.libraryVersion) {
        history.filter { e ->
            e.kind != "live" && e.inProgress && (!restrict || when (e.kind) {
                "movie" -> e.streamId.toIntOrNull()?.let { catalog.movieById[it]?.categoryId }?.let(restrictions::allowsCategory) == true
                "episode" -> e.seriesId?.let { catalog.seriesById[it]?.categoryId }?.let(restrictions::allowsCategory) == true
                else -> false
            })
        }.distinctBy { it.key }.take(20)
    }
    val recentChannels = remember(history, catalog.channels, restrict, catalog.adultCats) {
        history.filter { it.kind == "live" }
            .mapNotNull { e -> e.streamId.toIntOrNull()?.let { catalog.channelById[it] } }
            .filter { restrictions.allowsCategory(it.categoryId) }
            .distinctBy { it.id }.take(20)
    }
    val favs = store.favs(pid)
    val favChannels = remember(favs, catalog.channels, restrict, catalog.adultCats) {
        favs.filter { it.kind == "live" }
            .mapNotNull { catalog.channelById[it.id] }
            .filter { restrictions.allowsCategory(it.categoryId) }
            .distinctBy { it.id }
    }
    val myList = remember(favs, restrict, catalog.adultCats, catalog.libraryVersion) {
        favs.filter { f ->
            f.kind != "live" && (!restrict || when (f.kind) {
                "movie" -> catalog.movieById[f.id]?.categoryId?.let(restrictions::allowsCategory) == true
                "series" -> catalog.seriesById[f.id]?.categoryId?.let(restrictions::allowsCategory) == true
                else -> false
            })
        }.distinctBy { it.kind to it.id }
    }

    // Watch next: worked out off the screen thread whenever history, favourites, dismissals or the library change.
    val suggestions = app.suggestions
    store.dismissed(pid.orEmpty())
    val stamp = Suggestions.stamp(app, favs)
    LaunchedEffect(stamp) { suggestions.refresh(stamp) }
    val watchNext = suggestions.state.takeIf { it.stamp.startsWith(pid.orEmpty() + "|") }
    val upNext = watchNext?.upNext.orEmpty()
    val recs = watchNext?.rows ?: dev.relay.tv.data.Recommendations.Rows.EMPTY

    // The hero follows the focused card; before anything has focus it shows the first thing worth watching.
    var hero by remember { mutableStateOf<HeroFocus?>(null) }
    val firstHero = remember(continueWatching, upNext, recs, catalog.libraryVersion) {
        continueWatching.firstOrNull()?.let { heroOf(app, it) }
            ?: upNext.firstOrNull()?.let { heroOfUpNext(app, it) }
            ?: recs.forYou.firstOrNull()?.let { pick ->
                val img = if (pick.title.tv) catalog.seriesById[pick.title.id]?.cover else catalog.movieById[pick.title.id]?.poster
                HeroFocus.Title(pick.title.name, img, pick.title, reason = pick.reason)
            }
    }

    // Long press (or the Menu key) on a card offers to take it off its row.
    var removal by remember { mutableStateOf<Removal?>(null) }
    removal?.let { r ->
        val first = remember { FocusRequester() }
        InitialFocus(first)
        TvDialog({ removal = null }) {
            Text(r.action, fontSize = Type.title, fontWeight = FontWeight.Bold, color = Palette.text)
            Text(r.title, fontSize = Type.bodyLarge, color = Palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(r.confirm, { r.run(); removal = null }, focusRequester = first, primary = true, key = "rm:ok")
                PillButton("Cancel", { removal = null }, key = "rm:cancel")
            }
        }
    }

    val resume: (WatchEntry) -> Unit = { e ->
        val sid = e.seriesId
        if (e.kind == "episode" && sid != null) {
            scope.launch { Playback.resumeEpisode(ctx, e, if (e.inProgress) e.position else 0) }
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

    val tv = rememberIsTv()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val heroHeight = if (tv) maxHeight * 0.42f else (maxHeight * 0.38f).coerceIn(120.dp, 170.dp)
    Column(Modifier.fillMaxSize()) {
    HomeHero(hero ?: firstHero, heroHeight, compact = !tv)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().fadeEdges(), state = app.ui.homeList, contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp)) {
        rows.forEach { row ->
            item(key = row) {
                when {
                    row == HomeLayout.UP_NEXT -> Rail(HomeLayout.label(row), upNext, key = { it.key }, hint = "Hold OK to remove") { u ->
                        val series = catalog.seriesById[u.seriesId]
                        val ep = u.episode
                        PosterCard(
                            series?.name ?: "Next episode", ep.image ?: series?.cover,
                            {
                                val all = dev.relay.tv.data.UpNext.ordered(suggestions.episodesOf(u.seriesId).orEmpty())
                                val i = all.indexOfFirst { it.id == ep.id }
                                if (series != null && i >= 0) Playback.episodes(ctx, series, all, i, 0)
                            },
                            key = "un:${u.key}", width = 260.dp, aspect = 16f / 9f,
                            subtitle = "S${ep.season} E${ep.number} · ${ep.title}",
                            onFocused = { hero = heroOfUpNext(app, u) },
                            onLongClick = {
                                removal = Removal("Remove from Up next", series?.name ?: ep.title) { suggestions.hideUpNext(u.seriesId) }
                            },
                        )
                    }
                    row == HomeLayout.RECOMMENDED -> Rail(HomeLayout.label(row), recs.forYou, key = { it.title.key }, hint = "Hold OK: not interested") { pick ->
                        RecCard(pick, "rec", onFocused = { hero = it }) { removal = it }
                    }
                    row == HomeLayout.BECAUSE_1 || row == HomeLayout.BECAUSE_2 -> {
                        val group = recs.because.getOrNull(if (row == HomeLayout.BECAUSE_1) 0 else 1)
                        if (group != null) {
                            Rail(group.first.becauseLabel, group.second, key = { it.title.key }) { pick ->
                                RecCard(pick, row, onFocused = { hero = it }) { removal = it }
                            }
                        }
                    }
                    row == HomeLayout.CONTINUE -> Rail(
                        HomeLayout.label(row), continueWatching, key = { it.key }, hint = "Hold OK to remove",
                        footer = {
                            Box(Modifier.height(146.dp).padding(start = 6.dp), contentAlignment = Alignment.Center) {
                                PillButton(
                                    "Clear all",
                                    {
                                        val n = continueWatching.size
                                        removal = Removal("Clear Continue watching", "$n title${if (n == 1) "" else "s"} will be taken off this row.") {
                                            val gone = HistoryEdits.continueEntries(store.history(pid))
                                            store.clearContinue(pid)
                                            app.appScope.launch(Dispatchers.IO) { gone.forEach { WatchNext.remove(ctx, pid, it.key) } }
                                        }
                                    },
                                    key = "cw:clear",
                                )
                            }
                        },
                    ) { e ->
                        PosterCard(
                            e.title, e.image, { resume(e) }, key = "cw:${e.key}", width = 260.dp, aspect = 16f / 9f,
                            subtitle = e.subtitle ?: "${fmtDuration(e.duration - e.position)} left",
                            progress = e.position.toFloat() / e.duration.coerceAtLeast(1),
                            onFocused = { hero = heroOf(app, e) },
                            onLongClick = {
                                removal = Removal("Remove from Continue watching", e.title) {
                                    store.removeHistory(pid, e.key)
                                    app.appScope.launch(Dispatchers.IO) { WatchNext.remove(ctx, pid, e.key) }
                                }
                            },
                        )
                    }
                    row == HomeLayout.FAV_CHANNELS -> Rail(HomeLayout.label(row), favChannels, key = { it.id }) { ch ->
                        ChannelTile(
                            ch, { Playback.live(ctx, favChannels, favChannels.indexOf(ch)) },
                            key = "fc:${ch.id}", nowPlaying = catalog.nowPlaying(ch.id),
                            onFocused = { hero = HeroFocus.Live(ch) },
                        )
                    }
                    row == HomeLayout.RECENT_CHANNELS -> Rail(HomeLayout.label(row), recentChannels, key = { it.id }, hint = "Hold OK to remove") { ch ->
                        ChannelTile(
                            ch, { Playback.live(ctx, recentChannels, recentChannels.indexOf(ch)) },
                            key = "rc:${ch.id}", nowPlaying = catalog.nowPlaying(ch.id),
                            onFocused = { hero = HeroFocus.Live(ch) },
                            onLongClick = {
                                removal = Removal("Remove from Recently watched", ProviderNames.clean(ch.name)) {
                                    store.removeChannelHistory(pid, ch.id.toString())
                                }
                            },
                        )
                    }
                    row == HomeLayout.MY_LIST -> Rail(HomeLayout.label(row), myList, key = { "${it.kind}:${it.id}" }) { f ->
                        PosterCard(f.name, f.image, {
                            if (f.kind == "movie") catalog.movieById[f.id]?.takeIf { restrictions.allowsCategory(it.categoryId) }?.let { nav.push(Route.MovieDetail(it)) }
                            else catalog.seriesById[f.id]?.takeIf { restrictions.allowsCategory(it.categoryId) }?.let { nav.push(Route.SeriesDetail(it)) }
                        }, key = "ml:${f.kind}:${f.id}", onFocused = {
                            hero = HeroFocus.Title(f.name, f.image, suggestions.ref(f.kind == "series", f.id))
                        })
                    }
                    row.startsWith(HomeLayout.LANG) -> {
                        val lang = row.removePrefix(HomeLayout.LANG)
                        // Finding the newest titles in one language can mean walking the whole library, so it is done
                        // off the screen thread. The last answer stays up (and is kept while the row is scrolled away)
                        // until the new one is ready.
                        val inputs = listOf(catalog.libraryVersion, restrict, catalog.langVersion, hidden)
                        val cache = app.ui.homeLangRows
                        var list by remember(lang) { mutableStateOf(cache[lang]?.second ?: emptyList()) }
                        LaunchedEffect(lang, inputs) {
                            val kept = cache[lang]?.takeIf { it.first == inputs }?.second
                            val built = kept ?: withContext(Dispatchers.Default) { newIn(catalog, lang, restrict, hidden) }
                            if (kept == null) cache[lang] = inputs to built
                            list = built
                        }
                        Rail(HomeLayout.label(row), list, key = { it.railKey }) { t -> TitleCard(t, "nl:$lang") { hero = it } }
                    }
                    row == HomeLayout.NEW_MOVIES -> {
                        val list = remember(catalog.moviesByAdded, restrict, hidden) {
                            catalog.moviesByAdded.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "vod:${it.categoryId}" !in hidden }.take(30).toList()
                        }
                        Rail(HomeLayout.label(row), list, key = { it.id }) { m -> TitleCard(HomeTitle.M(m), "ram") { hero = it } }
                    }
                    row == HomeLayout.NEW_SERIES -> {
                        val list = remember(catalog.seriesByAdded, restrict, hidden) {
                            catalog.seriesByAdded.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "series:${it.categoryId}" !in hidden }.take(30).toList()
                        }
                        Rail(HomeLayout.label(row), list, key = { it.id }) { x -> TitleCard(HomeTitle.S(x), "ras") { hero = it } }
                    }
                    row == HomeLayout.TOP_MOVIES -> {
                        val list = remember(catalog.moviesByRating, restrict, hidden) {
                            catalog.moviesByRating.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "vod:${it.categoryId}" !in hidden }.take(30).toList()
                        }
                        Rail(HomeLayout.label(row), list, key = { it.id }) { m ->
                            PosterCard(
                                m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "trm:${m.id}", badge = "%.1f".format(m.rating), badgeStar = true,
                                onFocused = { hero = HeroFocus.Title(m.name, m.poster, suggestions.ref(false, m.id)) },
                            )
                        }
                    }
                    row == HomeLayout.TOP_SERIES -> {
                        val list = remember(catalog.seriesByRating, restrict, hidden) {
                            catalog.seriesByRating.asSequence().filter { catalog.allowed(it.categoryId, restrict) && "series:${it.categoryId}" !in hidden }.take(30).toList()
                        }
                        Rail(HomeLayout.label(row), list, key = { it.id }) { x ->
                            PosterCard(
                                x.name, x.cover, { nav.push(Route.SeriesDetail(x)) }, key = "trs:${x.id}", badge = "%.1f".format(x.rating), badgeStar = true,
                                onFocused = { hero = HeroFocus.Title(x.name, x.cover, suggestions.ref(true, x.id)) },
                            )
                        }
                    }
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

    /** Unique within a row (a movie and a series can share a number). */
    val railKey: String

    data class M(val movie: dev.relay.tv.data.Movie) : HomeTitle {
        override val added get() = movie.added
        override val railKey get() = "m${movie.id}"
    }

    data class S(val series: dev.relay.tv.data.Series) : HomeTitle {
        override val added get() = series.added
        override val railKey get() = "s${series.id}"
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
private fun TitleCard(t: HomeTitle, keyPrefix: String, onHero: (HeroFocus) -> Unit = {}) {
    val nav = LocalNav.current
    val sug = RelayApp.instance.suggestions
    when (t) {
        is HomeTitle.M -> {
            val m = t.movie
            val rt = posterScore("m", m.id)
            PosterCard(
                m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "$keyPrefix:m:${m.id}", subtitle = m.year,
                badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
                onFocused = { onHero(HeroFocus.Title(m.name, m.poster, sug.ref(false, m.id))) },
            )
        }
        is HomeTitle.S -> {
            val x = t.series
            val rt = posterScore("s", x.id)
            PosterCard(
                x.name, x.cover, { nav.push(Route.SeriesDetail(x)) }, key = "$keyPrefix:s:${x.id}", subtitle = x.year,
                badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
                onFocused = { onHero(HeroFocus.Title(x.name, x.cover, sug.ref(true, x.id))) },
            )
        }
    }
}

/** A recommended title: opens its page; holding OK offers "Not interested". */
@Composable
private fun RecCard(pick: dev.relay.tv.data.Recommendations.Pick, keyPrefix: String, onFocused: (HeroFocus) -> Unit, onRemove: (Removal) -> Unit) {
    val app = RelayApp.instance
    val nav = LocalNav.current
    val t = pick.title
    val image = if (t.tv) app.catalog.seriesById[t.id]?.cover else app.catalog.movieById[t.id]?.poster
    PosterCard(
        t.name, image,
        {
            if (t.tv) app.catalog.seriesById[t.id]?.let { nav.push(Route.SeriesDetail(it)) }
            else app.catalog.movieById[t.id]?.let { nav.push(Route.MovieDetail(it)) }
        },
        key = "$keyPrefix:${t.key}", subtitle = t.year,
        onFocused = { onFocused(HeroFocus.Title(t.name, image, t, reason = pick.reason)) },
        onLongClick = {
            onRemove(Removal("Not interested", "${dev.relay.tv.data.TitleParts.parse(t.name).title} won't be recommended to this profile again.", "Not interested") {
                app.suggestions.notInterested(t)
            })
        },
    )
}

/** The hero for a Continue watching entry. */
private fun heroOf(app: RelayApp, e: WatchEntry): HeroFocus.Title {
    val ref = when (e.kind) {
        "movie" -> e.streamId.toIntOrNull()?.let { app.suggestions.ref(false, it) }
        "episode" -> e.seriesId?.let { app.suggestions.ref(true, it) }
        else -> null
    }
    val left = "${fmtDuration(e.duration - e.position)} left"
    return HeroFocus.Title(e.title, e.image, ref, subtitle = listOfNotNull(e.subtitle, left).joinToString("  ·  "), wide = e.image.takeIf { e.kind == "episode" })
}

/** The hero for an Up next episode. */
private fun heroOfUpNext(app: RelayApp, u: dev.relay.tv.data.UpNext.Item): HeroFocus.Title {
    val s = app.catalog.seriesById[u.seriesId]
    return HeroFocus.Title(
        s?.name ?: u.episode.title, s?.cover, app.suggestions.ref(true, u.seriesId),
        subtitle = "Up next: S${u.episode.season} E${u.episode.number} · ${u.episode.title}", wide = u.episode.image,
    )
}
