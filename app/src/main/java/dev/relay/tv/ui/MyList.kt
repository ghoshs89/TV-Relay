package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.FavItem
import dev.relay.tv.player.Playback

/**
 * My list: the profile's saved films and series and its favourite channels in one place, with chips to
 * show one kind. Holding OK takes an item off the list. Kids and adult rules apply as everywhere else.
 */
@Composable
fun MyListTab() {
    val app = RelayApp.instance
    val store = app.store
    val catalog = app.catalog
    val nav = LocalNav.current
    val ctx = LocalContext.current
    store.version.intValue
    val pid = app.session.profileId.orEmpty()
    val restrict = app.session.restrict(store.settings)
    val access = remember(restrict, catalog.adultCats, catalog.libraryVersion) { catalog.restrictions(restrict) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var removing by remember { mutableStateOf<FavItem?>(null) }

    val favs = store.favs(pid)
    val items = remember(favs, filter, access, catalog.libraryVersion) {
        favs.filter { f ->
            val cat = when (f.kind) {
                "movie" -> catalog.movieById[f.id]?.categoryId
                "series" -> catalog.seriesById[f.id]?.categoryId
                "live" -> catalog.channelById[f.id]?.categoryId
                else -> null
            }
            cat != null && access.allowsCategory(cat) && (filter == "all" || f.kind == filter)
        }.distinctBy { it.kind to it.id }
    }
    val channels = remember(items) { items.filter { it.kind == "live" }.mapNotNull { catalog.channelById[it.id] } }

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Text("My list", fontSize = Type.title, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(end = 16.dp))
            listOf("all" to "All", "movie" to "Movies", "series" to "Series", "live" to "Live TV").forEach { (k, label) ->
                Chip(label, filter == k, "my:$k", compact = true) { filter = k }
            }
        }
        if (items.isEmpty()) {
            EmptyState(
                if (favs.isEmpty()) "Nothing on your list yet. Add films and series with My list on their page, and favourite channels by holding OK."
                else "Nothing of this kind on your list.",
            )
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp),
            Modifier.fillMaxSize().fadeEdges(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { "${it.kind}:${it.id}" }) { f ->
                when (f.kind) {
                    "live" -> catalog.channelById[f.id]?.let { ch ->
                        ChannelTile(
                            ch, { Playback.live(ctx, channels, channels.indexOf(ch).coerceAtLeast(0)) }, key = "my:live:${f.id}",
                            nowPlaying = catalog.nowPlaying(ch.id), width = 150.dp, onLongClick = { removing = f },
                        )
                    }
                    else -> PosterCard(
                        f.name, f.image,
                        {
                            if (f.kind == "movie") catalog.movieById[f.id]?.let { nav.push(Route.MovieDetail(it)) }
                            else catalog.seriesById[f.id]?.let { nav.push(Route.SeriesDetail(it)) }
                        },
                        key = "my:${f.kind}:${f.id}", onLongClick = { removing = f },
                    )
                }
            }
        }
    }

    removing?.let { f ->
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        InitialFocus(first)
        TvDialog({ removing = null }) {
            Text("Remove from My list", fontSize = Type.title, fontWeight = FontWeight.Bold, color = Palette.text)
            Text(f.name, fontSize = Type.bodyLarge, color = Palette.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Remove", { store.toggleFav(pid, f); removing = null }, focusRequester = first, primary = true, key = "my:rm")
                PillButton("Cancel", { removing = null }, key = "my:cancel")
            }
        }
    }
}
