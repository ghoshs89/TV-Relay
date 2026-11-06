package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Category
import dev.relay.tv.data.Movie
import dev.relay.tv.data.Series
import kotlinx.coroutines.launch

@Composable
fun MoviesTab() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val store = app.store
    val nav = LocalNav.current
    val version = store.version.intValue
    val pid = app.session.profileId
    val restrict = app.session.restrict(store.settings)

    val cats = remember(catalog.vodCats, restrict) {
        specials() + catalog.vodCats.filter { !(restrict && it.adult) }
    }
    val items: List<Movie> = remember(ui.movieCat, catalog.movies, restrict, version) {
        val base = when (ui.movieCat) {
            UiState.RECENT -> catalog.moviesByAdded
            UiState.TOP -> catalog.moviesByRating
            UiState.FAV -> store.favs(pid).filter { it.kind == "movie" }.mapNotNull { catalog.movieById[it.id] }
            UiState.ALL -> catalog.movies.sortedBy { it.name.lowercase() }
            else -> catalog.moviesByCat[ui.movieCat].orEmpty()
        }
        if (restrict) base.filter { catalog.allowed(it.categoryId, true) } else base
    }
    Browser(
        cats = cats,
        selected = ui.movieCat,
        onSelect = { ui.movieCat = it },
        counts = { id -> catalog.moviesByCat[id]?.size },
        catState = ui.movieCatList,
        gridState = ui.movieGrid,
        items = items,
        empty = if (ui.movieCat == UiState.FAV) "Movies you add to My list show up here." else "Nothing here.",
    ) { m ->
        PosterCard(
            m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "mv:${m.id}",
            subtitle = listOfNotNull(m.year, m.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString("  "),
        )
    }
}

@Composable
fun SeriesTab() {
    val app = RelayApp.instance
    val ui = app.ui
    val catalog = app.catalog
    val store = app.store
    val nav = LocalNav.current
    val version = store.version.intValue
    val pid = app.session.profileId
    val restrict = app.session.restrict(store.settings)

    val cats = remember(catalog.seriesCats, restrict) {
        specials() + catalog.seriesCats.filter { !(restrict && it.adult) }
    }
    val items: List<Series> = remember(ui.seriesCat, catalog.series, restrict, version) {
        val base = when (ui.seriesCat) {
            UiState.RECENT -> catalog.seriesByAdded
            UiState.TOP -> catalog.seriesByRating
            UiState.FAV -> store.favs(pid).filter { it.kind == "series" }.mapNotNull { catalog.seriesById[it.id] }
            UiState.ALL -> catalog.series.sortedBy { it.name.lowercase() }
            else -> catalog.seriesByCat[ui.seriesCat].orEmpty()
        }
        if (restrict) base.filter { catalog.allowed(it.categoryId, true) } else base
    }
    Browser(
        cats = cats,
        selected = ui.seriesCat,
        onSelect = { ui.seriesCat = it },
        counts = { id -> catalog.seriesByCat[id]?.size },
        catState = ui.seriesCatList,
        gridState = ui.seriesGrid,
        items = items,
        empty = if (ui.seriesCat == UiState.FAV) "Series you add to My list show up here." else "Nothing here.",
    ) { s ->
        PosterCard(
            s.name, s.cover, { nav.push(Route.SeriesDetail(s)) }, key = "sr:${s.id}",
            subtitle = listOfNotNull(s.year, s.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString("  "),
        )
    }
}

private fun specials() = listOf(
    Category(UiState.RECENT, "Recently added"),
    Category(UiState.TOP, "Top rated"),
    Category(UiState.FAV, "★ My list"),
    Category(UiState.ALL, "All A–Z"),
)

@Composable
private fun <T> Browser(
    cats: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
    counts: (String) -> Int?,
    catState: LazyListState,
    gridState: LazyGridState,
    items: List<T>,
    empty: String,
    card: @Composable (T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 16.dp)) {
        LazyColumn(
            Modifier.width(250.dp).fillMaxHeight(),
            state = catState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(cats) { c ->
                ListRow(
                    c.name,
                    onClick = {
                        onSelect(c.id)
                        scope.launch { gridState.scrollToItem(0) }
                    },
                    selected = selected == c.id,
                    key = "cat:${c.id}",
                    trailing = counts(c.id)?.toString(),
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        if (items.isEmpty()) {
            EmptyState(empty, Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                state = gridState,
                contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp, start = 10.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(items) { card(it) }
            }
        }
    }
}
