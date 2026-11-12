package dev.relay.tv.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.data.EpgEntry
import android.widget.Toast
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.Movie
import dev.relay.tv.data.SearchText
import dev.relay.tv.data.Series
import dev.relay.tv.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Search tab: channels, programmes on now, movies and series matching what you type.

private class Results(
    val onTv: List<Pair<Channel, EpgEntry>> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
)

@Composable
fun SearchTab() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val restrict = app.session.restrict(app.store.settings)
    val focusManager = LocalFocusManager.current
    var results by remember { mutableStateOf(Results()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(ui.query, catalog.channels, catalog.movies, catalog.series, restrict) {
        delay(dev.relay.tv.data.DeviceTier.current.searchPauseMs)
        val match = SearchText.matcher(ui.query)
        val ready = SearchText.matcherNormalized(ui.query)
        if (match == null || ready == null) {
            results = Results()
            return@LaunchedEffect
        }
        searching = true
        val t0 = System.currentTimeMillis()
        val rank = app.session.langRank(app.store.settings)
        results = withContext(Dispatchers.Default) {
            // Gather more than shown, then put your languages first.
            val hidden = app.store.settings.hiddenCats
            Results(
                onTv = catalog.searchGuide(match).filter { (ch, _) ->
                    catalog.allowed(ch.categoryId, restrict) && "live:${ch.categoryId}" !in hidden
                }.sortedBy { (ch, _) -> rank.rank(catalog.channelLang(ch.id)) }.take(30),
                channels = found(catalog.channels, catalog.searchChannels, 400, ready) { catalog.allowed(it.categoryId, restrict) }
                    .sortedBy { rank.rank(catalog.channelLang(it.id)) }.take(40),
                movies = found(catalog.movies, catalog.searchMovies, 600, ready) { catalog.allowed(it.categoryId, restrict) }
                    .sortedBy { rank.rank(catalog.movieLang(it.id)) }.take(60),
                series = found(catalog.series, catalog.searchSeries, 600, ready) { catalog.allowed(it.categoryId, restrict) }
                    .sortedBy { rank.rank(catalog.seriesLang(it.id)) }.take(60),
            )
        }
        searching = false
        AppLog.d("Search", "\"${ui.query}\" searched in ${System.currentTimeMillis() - t0} ms")
    }

    val relay = useRelayKeyboard()
    val entry = remember { FocusRequester() }
    // Opening Search goes straight to typing.
    InitialFocus(entry)

    val r = results
    val resultRows: LazyListScope.() -> Unit = {
        if (ui.query.isNotBlank() && !searching && r.onTv.isEmpty() && r.channels.isEmpty() && r.movies.isEmpty() && r.series.isEmpty()) {
            item { Text("No matches for \"${ui.query}\"", Modifier.padding(horizontal = 48.dp), color = Palette.dim) }
        }
        if (ui.query.isBlank()) {
            item {
                Text(
                    "Start typing: results appear as you go.", Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    color = Palette.dim,
                )
            }
        }
        item {
            Rail("On TV", r.onTv) { (ch, e) ->
                ProgrammeCard(ch, e) {
                    val nowSec = System.currentTimeMillis() / 1000
                    if (e.start <= nowSec) {
                        Playback.live(ctx, listOf(ch), 0)
                    } else {
                        Toast.makeText(ctx, ReminderCenter.toggle(ch, e), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item {
            Rail("Channels", r.channels) { ch ->
                ChannelTile(
                    ch, { Playback.live(ctx, r.channels, r.channels.indexOf(ch)) },
                    key = "s:ch:${ch.id}", nowPlaying = catalog.nowPlaying(ch.id),
                )
            }
        }
        item {
            Rail("Movies", r.movies) { m ->
                val rt = posterScore("m", m.id)
                PosterCard(
                    m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "s:mv:${m.id}", subtitle = m.year,
                    badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
                )
            }
        }
        item {
            Rail("Series", r.series) { s ->
                val rt = posterScore("s", s.id)
                PosterCard(
                    s.name, s.cover, { nav.push(Route.SeriesDetail(s)) }, key = "s:sr:${s.id}", subtitle = s.year,
                    badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
                )
            }
        }
    }

    if (relay) {
        // Relay keyboard docked on the left; results update on the right while typing.
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(420.dp).padding(start = 32.dp, top = 16.dp, end = 4.dp)) {
                Box(
                    Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (ui.query.isEmpty()) "Search channels, movies, series" else ui.query + "▏",
                        color = if (ui.query.isEmpty()) Palette.dim else Palette.text,
                        fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                RelayKeyboard(
                    value = ui.query,
                    onValueChange = { ui.query = it },
                    onDone = { focusManager.moveFocus(FocusDirection.Right) },
                    firstKey = entry,
                    keyHeight = 40.dp,
                    doneLabel = "Results",
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().fadeEdges(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                content = resultRows,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)) {
            item {
                Row(Modifier.padding(horizontal = 48.dp, vertical = 12.dp)) {
                    TvTextField(
                        ui.query, { ui.query = it }, "Search channels, movies and series",
                        Modifier.width(680.dp), imeAction = ImeAction.Search,
                        focusRequester = entry, autoOpen = true,
                        // Keyboard closes on the search key; focus jumps to the first result.
                        onDone = { focusManager.moveFocus(FocusDirection.Down) },
                    )
                }
            }
            resultRows()
        }
    }
}

private val progTime = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())

/** A programme found in the guide: on now (OK watches) or later (OK sets a reminder). */
@Composable
private fun ProgrammeCard(ch: Channel, e: EpgEntry, onClick: () -> Unit) {
    val nowSec = System.currentTimeMillis() / 1000
    val live = e.start <= nowSec
    val reminder = !live && ReminderCenter.isSet(ch.id, e.start)
    Focusable(onClick, Modifier.width(280.dp).height(110.dp), key = "s:tv:${ch.id}:${e.start}", shape = RoundedCornerShape(10.dp)) { f ->
        Column(
            Modifier.fillMaxSize().background(if (f) Palette.surface2 else Palette.surface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                if (live) "NOW · OK to watch" else progTime.format(java.util.Date(e.start * 1000)) + if (reminder) " · Reminder set" else "",
                color = if (live) Palette.accent else Palette.dim, fontSize = Type.label, fontWeight = FontWeight.SemiBold,
            )
            Text(e.title, color = Palette.text, fontSize = Type.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ProviderNames.clean(ch.name), color = Palette.dim, fontSize = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The first [limit] items of [list], in library order, that are [allowed] and whose name matches. Names
 * are compared in their prepared form (kept by [names]), so only the first search after a library loads
 * pays for preparing them.
 */
private fun <T> found(
    list: List<T>,
    names: dev.relay.tv.data.NormalizedNames<T>,
    limit: Int,
    match: (String) -> Boolean,
    allowed: (T) -> Boolean,
): List<T> {
    val prepared = names.of(list)
    val out = ArrayList<T>()
    for (i in list.indices) {
        val item = list[i]
        if (allowed(item) && match(prepared[i])) {
            out += item
            if (out.size >= limit) break
        }
    }
    return out
}
