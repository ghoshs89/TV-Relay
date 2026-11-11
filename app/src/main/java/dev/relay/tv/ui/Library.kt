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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Category
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.data.Langs
import dev.relay.tv.data.TitleParts
import dev.relay.tv.data.TitleQuery
import dev.relay.tv.data.TitleSelection
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
    val list = rememberTitleList(
        "Movies", titleQuery(ui.movieCat, "movie", "vod", if (ui.movieCat == UiState.FAV) store.favs(pid) else null, restrict, catalog.adultCats, s.hiddenCats, app.session.langOrder(s)),
        titleGeneration(catalog), ui.movieLists, catalog.movieSource,
    )
    Browser(
        cats = cats,
        selected = ui.movieCat,
        onSelect = { ui.movieCat = it },
        counts = { id -> catalog.moviesByCat[id]?.size },
        catState = ui.movieCatList,
        gridState = ui.movieGrid,
        items = list.items,
        itemKey = { it.id },
        stale = list.stale,
        empty = when {
            catalog.loading && catalog.movies.isEmpty() -> "Loading movies…"
            ui.movieCat == UiState.FAV -> "Movies you add to My list show up here."
            else -> "Nothing here."
        },
        pinnedIds = remember(s.pinnedVod) { s.pinnedVod.toSet() },
    ) { m ->
        val rt = posterScore("m", m.id)
        val tp = remember(m.name, m.year) { TitleParts.parse(m.name, m.year) }
        PosterCard(
            m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "mv:${m.id}", titleParts = tp,
            subtitle = listOfNotNull(tp.year, tp.region, m.rating.takeIf { it > 0 }?.let { "%.1f/10".format(it) }).joinToString(" · "),
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
    val list = rememberTitleList(
        "Series", titleQuery(ui.seriesCat, "series", "series", if (ui.seriesCat == UiState.FAV) store.favs(pid) else null, restrict, catalog.adultCats, s.hiddenCats, app.session.langOrder(s)),
        titleGeneration(catalog), ui.seriesLists, catalog.seriesSource,
    )
    Browser(
        cats = cats,
        selected = ui.seriesCat,
        onSelect = { ui.seriesCat = it },
        counts = { id -> catalog.seriesByCat[id]?.size },
        catState = ui.seriesCatList,
        gridState = ui.seriesGrid,
        items = list.items,
        itemKey = { it.id },
        stale = list.stale,
        empty = when {
            catalog.loading && catalog.series.isEmpty() -> "Loading series…"
            ui.seriesCat == UiState.FAV -> "Series you add to My list show up here."
            else -> "Nothing here."
        },
        pinnedIds = remember(s.pinnedSeries) { s.pinnedSeries.toSet() },
    ) { s ->
        val rt = posterScore("s", s.id)
        val tp = remember(s.name, s.year) { TitleParts.parse(s.name, s.year) }
        PosterCard(
            s.name, s.cover, { nav.push(Route.SeriesDetail(s)) }, key = "sr:${s.id}", titleParts = tp,
            subtitle = listOfNotNull(tp.year, tp.region, s.rating.takeIf { it > 0 }?.let { "%.1f/10".format(it) }).joinToString(" · "),
            badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
        )
    }
}

const val LANG = "__lang:"

/** Changes when the library is replaced or the language guesses move, which makes cached lists stale. */
private fun titleGeneration(catalog: dev.relay.tv.data.Catalog): Long =
    (catalog.libraryVersion.toLong() shl 32) or (catalog.langVersion.toLong() and 0xFFFFFFFFL)

/**
 * What one Movies or Series list needs from the screen and the store, and nothing else, so that
 * a write that doesn't touch it leaves the query equal and the list isn't built again.
 * [favs] is only passed for My list.
 */
private fun titleQuery(
    cat: String,
    favKind: String,
    hiddenKind: String,
    favs: List<dev.relay.tv.data.FavItem>?,
    restrict: Boolean,
    adultCats: Set<String>,
    hiddenCats: Set<String>,
    langOrder: List<String>,
): TitleQuery {
    val selection = when {
        cat == UiState.RECENT -> TitleSelection.Recent
        cat == UiState.TOP -> TitleSelection.Top
        cat == UiState.FAV -> TitleSelection.Favourites(favs.orEmpty().filter { it.kind == favKind }.map { it.id })
        cat == UiState.ALL -> TitleSelection.All
        cat.startsWith(LANG) -> TitleSelection.Language(cat.removePrefix(LANG))
        else -> TitleSelection.Category(cat)
    }
    val prefix = "$hiddenKind:"
    val mixed = selection == TitleSelection.Recent || selection == TitleSelection.Top || selection == TitleSelection.All
    return TitleQuery(
        selection = selection,
        blockedCats = if (restrict) adultCats else emptySet(),
        hiddenCats = if (mixed) hiddenCats.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet() else emptySet(),
        langOrder = if (mixed) langOrder else emptyList(),
    )
}

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
        top.map { it.copy(name = ProviderNames.clean(it.name)) } + rest
}

private fun specials() = listOf(
    Category(UiState.RECENT, "Recently added"),
    Category(UiState.TOP, "Top rated"),
    Category(UiState.FAV, "My list"),
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
    items: List<T>?,
    /** Stable id of a title, for keying grid cells. Lists from [buildTitleList] hold each id once. */
    itemKey: (T) -> Any,
    /** [items] belong to the previous category and are being replaced: shown dimmed, not selectable. */
    stale: Boolean = false,
    empty: String,
    pinnedIds: Set<String>,
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
                    icon = when {
                        c.id == UiState.FAV -> Icons.Filled.Star
                        c.id in pinnedIds -> Icons.Filled.Place
                        c.id.startsWith(LANG) -> RelayIcons.Language
                        else -> null
                    },
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        if (items == null || (stale && items.isEmpty())) {
            EmptyState("Loading…", Modifier.weight(1f))
        } else if (items.isEmpty()) {
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
