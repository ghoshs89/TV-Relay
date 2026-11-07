package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.data.Langs
import dev.relay.tv.data.TitleParts
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

    val s = store.settings
    val rank = app.session.langRank(s)
    val featured = app.session.langFeatured(s)
    val cats = remember(catalog.vodCats, restrict, version, catalog.langVersion) {
        arrangeCats(catalog.vodCats, "vod", s.pinnedVod, s.hiddenCats, restrict, featured) { rank.rank(catalog.vodCatLang(it)) }
    }
    val items: List<Movie> = remember(ui.movieCat, catalog.movies, restrict, version, catalog.langVersion) {
        val cat = ui.movieCat
        val base = when {
            cat == UiState.RECENT -> catalog.moviesByAdded
            cat == UiState.TOP -> catalog.moviesByRating
            cat == UiState.FAV -> store.favs(pid).filter { it.kind == "movie" }.mapNotNull { catalog.movieById[it.id] }
            cat == UiState.ALL -> catalog.movies.sortedBy { it.name.lowercase() }
            cat.startsWith(LANG) -> catalog.moviesByAdded.filter { catalog.movieLang(it.id) == cat.removePrefix(LANG) }
            else -> catalog.moviesByCat[cat].orEmpty()
        }
        val allowed = base.filter { catalog.allowed(it.categoryId, restrict) }
        // Mixed lists: hidden categories stay out, and your languages come first (nothing is removed).
        if (cat == UiState.RECENT || cat == UiState.TOP || cat == UiState.ALL) {
            allowed.filter { "vod:${it.categoryId}" !in s.hiddenCats }.sortedBy { rank.rank(catalog.movieLang(it.id)) }
        } else {
            allowed
        }
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
        val rt = posterScore("m", m.id)
        val tp = remember(m.name, m.year) { TitleParts.parse(m.name, m.year) }
        PosterCard(
            m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "mv:${m.id}",
            subtitle = listOfNotNull(tp.year, tp.region, m.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "),
            badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
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

    val s = store.settings
    val rank = app.session.langRank(s)
    val featured = app.session.langFeatured(s)
    val cats = remember(catalog.seriesCats, restrict, version, catalog.langVersion) {
        arrangeCats(catalog.seriesCats, "series", s.pinnedSeries, s.hiddenCats, restrict, featured) { rank.rank(catalog.seriesCatLang(it)) }
    }
    val items: List<Series> = remember(ui.seriesCat, catalog.series, restrict, version, catalog.langVersion) {
        val cat = ui.seriesCat
        val base = when {
            cat == UiState.RECENT -> catalog.seriesByAdded
            cat == UiState.TOP -> catalog.seriesByRating
            cat == UiState.FAV -> store.favs(pid).filter { it.kind == "series" }.mapNotNull { catalog.seriesById[it.id] }
            cat == UiState.ALL -> catalog.series.sortedBy { it.name.lowercase() }
            cat.startsWith(LANG) -> catalog.seriesByAdded.filter { catalog.seriesLang(it.id) == cat.removePrefix(LANG) }
            else -> catalog.seriesByCat[cat].orEmpty()
        }
        val allowed = base.filter { catalog.allowed(it.categoryId, restrict) }
        if (cat == UiState.RECENT || cat == UiState.TOP || cat == UiState.ALL) {
            allowed.filter { "series:${it.categoryId}" !in s.hiddenCats }.sortedBy { rank.rank(catalog.seriesLang(it.id)) }
        } else {
            allowed
        }
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
        val rt = posterScore("s", s.id)
        val tp = remember(s.name, s.year) { TitleParts.parse(s.name, s.year) }
        PosterCard(
            s.name, s.cover, { nav.push(Route.SeriesDetail(s)) }, key = "sr:${s.id}",
            subtitle = listOfNotNull(tp.year, tp.region, s.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "),
            badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
        )
    }
}

const val LANG = "__lang:"

/**
 * Category list for Movies or Series: Relay's own lists, one per featured language, pinned
 * categories, then the rest with your languages first. Hidden categories are left out.
 */
private fun arrangeCats(
    cats: List<Category>,
    type: String,
    pinned: List<String>,
    hidden: Set<String>,
    restrict: Boolean,
    featured: List<String>,
    rank: (String) -> Int,
): List<Category> {
    val visible = cats.filter { !(restrict && it.adult) && "$type:${it.id}" !in hidden }
    val byId = visible.associateBy { it.id }
    val top = pinned.mapNotNull { byId[it] }
    val topIds = top.map { it.id }.toSet()
    val rest = visible.filter { it.id !in topIds }.sortedBy { rank(it.id) }
    return specials() + featured.map { Category("$LANG$it", Langs.label(it)) } +
        top.map { it.copy(name = "📌 ${ProviderNames.clean(it.name)}") } + rest
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val narrow = maxWidth < 900.dp
    Row(Modifier.fillMaxSize().padding(start = if (narrow) 16.dp else 32.dp, end = 16.dp)) {
        LazyColumn(
            Modifier.width(if (narrow) 180.dp else 230.dp).fillMaxHeight().fadeEdges(),
            state = catState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 20.dp),
        ) {
            items(cats) { c ->
                ListRow(
                    if (c.id.startsWith("__")) c.name else ProviderNames.clean(c.name),
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
                modifier = Modifier.weight(1f).fillMaxHeight().fadeEdges(),
                state = gridState,
                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp, start = 10.dp, end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(items) { card(it) }
            }
        }
    }
    }
}
