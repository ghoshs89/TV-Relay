package dev.relay.tv.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Category
import dev.relay.tv.data.ContentKinds
import dev.relay.tv.data.Langs
import dev.relay.tv.data.ProviderNames
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
    val langOrder = app.session.langOrder(s)
    val cats = remember(catalog.vodCats, restrict, version, catalog.langVersion) {
        arrangeCats(catalog.vodCats, "vod", s.pinnedVod, s.hiddenCats, restrict, featured) { rank.rank(catalog.vodCatLang(it)) }
    }
    val availableKinds = remember(catalog.vodCats, catalog.moviesByAdded, restrict, s.hiddenCats, catalog.libraryVersion, catalog.langVersion) {
        val visible = catalog.vodCats.filter { !(restrict && it.adult) && "vod:${it.id}" !in s.hiddenCats }
        val catPresent = visible.mapNotNull { catalog.vodCatKindOf(it.id) }.toSet()
        ContentKinds.TITLE_ORDER.filter { kind ->
            kind in catPresent || catalog.moviesByAdded.asSequence().take(400).any {
                ContentKinds.matchesApiGenres(catalog.movieMeta(it.id)?.genres.orEmpty(), kind)
            }
        }
    }
    val mixedView = ui.movieCat == UiState.RECENT || ui.movieCat == UiState.TOP || ui.movieCat == UiState.ALL
    val activeLang = ui.movieLangFilter?.takeIf { mixedView }
    val activeKind = ui.movieKindFilter?.takeIf { it in availableKinds }
    val list = rememberTitleList(
        "Movies",
        titleQuery(
            ui.movieCat, "movie", "vod",
            if (ui.movieCat == UiState.FAV) store.favs(pid) else null,
            restrict, catalog.adultCats, s.hiddenCats, langOrder, activeLang, activeKind, ui.movieDubbedFilter,
        ),
        titleGeneration(catalog), ui.movieLists, catalog.movieSource,
    )
    // Enrich visible Hindi/India/Asia/Dubbed/Kids movies via TMDB/MDBList in background.
    val visibleHeadId = list.items?.firstOrNull()?.id
    LaunchedEffect(ui.movieCat, activeLang, ui.movieDubbedFilter, visibleHeadId) {
        val items = list.items ?: return@LaunchedEffect
        val catName = catalog.vodCatName(ui.movieCat).uppercase()
        val shouldEnrich = ui.movieDubbedFilter ||
            activeLang == Langs.HI ||
            ui.movieCat == "$LANG${Langs.HI}" ||
            "HINDI" in catName || "INDIA" in catName || "ASIA" in catName || "DUB" in catName ||
            "KIDS" in catName || "ANIME" in catName
        if (shouldEnrich) app.enricher.enqueueVisibleMovies(items)
    }

    var focusedHero by remember { mutableStateOf<HeroFocus?>(null) }
    val firstHero = remember(list.items?.firstOrNull()?.id, catalog.langVersion) {
        list.items?.firstOrNull()?.let { m ->
            HeroFocus.Title(m.name, m.poster, app.suggestions.ref(false, m.id))
        }
    }

    Browser(
        cats = cats,
        selected = ui.movieCat,
        onSelect = { ui.movieCat = it },
        counts = { id -> catalog.moviesByCat[id]?.size },
        gridState = ui.movieGrid,
        items = list.items,
        itemKey = { it.id },
        stale = list.stale,
        empty = when {
            catalog.loading && catalog.movies.isEmpty() -> "Loading movies…"
            activeLang != null || activeKind != null || ui.movieDubbedFilter -> "No movies match these filters."
            ui.movieCat == UiState.FAV -> "Movies you add to My list show up here."
            else -> "Nothing here."
        },
        pinnedIds = remember(s.pinnedVod) { s.pinnedVod.toSet() },
        langChips = if (mixedView) Langs.viewOrder(langOrder) else emptyList(),
        activeLang = activeLang,
        onLangSelect = { ui.movieLangFilter = if (activeLang == it) null else it },
        dubbedOnly = ui.movieDubbedFilter,
        onDubbedToggle = { ui.movieDubbedFilter = !ui.movieDubbedFilter },
        kindChips = availableKinds,
        activeKind = activeKind,
        onKindSelect = { ui.movieKindFilter = if (activeKind == it) null else it },
        hero = focusedHero ?: firstHero,
        pickerTitle = "Movie categories",
    ) { m ->
        val rt = posterScore("m", m.id)
        val tp = remember(m.name, m.year) { TitleParts.parse(m.name, m.year) }
        val sub = remember(tp, m.rating) { listOfNotNull(tp.year, tp.region, m.rating.takeIf { it > 0 }?.let { "%.1f/10".format(it) }).joinToString(" · ") }
        PosterCard(
            m.name, m.poster, { nav.push(Route.MovieDetail(m)) }, key = "mv:${m.id}", titleParts = tp,
            subtitle = sub,
            badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
            onFocused = { focusedHero = HeroFocus.Title(m.name, m.poster, app.suggestions.ref(false, m.id)) },
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
    val langOrder = app.session.langOrder(s)
    val cats = remember(catalog.seriesCats, restrict, version, catalog.langVersion) {
        arrangeCats(catalog.seriesCats, "series", s.pinnedSeries, s.hiddenCats, restrict, featured) { rank.rank(catalog.seriesCatLang(it)) }
    }
    val availableKinds = remember(catalog.seriesCats, catalog.seriesByAdded, restrict, s.hiddenCats, catalog.libraryVersion, catalog.langVersion) {
        val visible = catalog.seriesCats.filter { !(restrict && it.adult) && "series:${it.id}" !in s.hiddenCats }
        val catPresent = visible.mapNotNull { catalog.seriesCatKindOf(it.id) }.toSet()
        ContentKinds.TITLE_ORDER.filter { kind ->
            kind in catPresent || catalog.seriesByAdded.asSequence().take(400).any {
                ContentKinds.matchesApiGenres(catalog.seriesMeta(it.id)?.genres.orEmpty(), kind)
            }
        }
    }
    val mixedView = ui.seriesCat == UiState.RECENT || ui.seriesCat == UiState.TOP || ui.seriesCat == UiState.ALL
    val activeLang = ui.seriesLangFilter?.takeIf { mixedView }
    val activeKind = ui.seriesKindFilter?.takeIf { it in availableKinds }
    val list = rememberTitleList(
        "Series",
        titleQuery(
            ui.seriesCat, "series", "series",
            if (ui.seriesCat == UiState.FAV) store.favs(pid) else null,
            restrict, catalog.adultCats, s.hiddenCats, langOrder, activeLang, activeKind, ui.seriesDubbedFilter,
        ),
        titleGeneration(catalog), ui.seriesLists, catalog.seriesSource,
    )
    val visibleHeadId = list.items?.firstOrNull()?.id
    LaunchedEffect(ui.seriesCat, activeLang, ui.seriesDubbedFilter, visibleHeadId) {
        val items = list.items ?: return@LaunchedEffect
        val catName = catalog.seriesCatName(ui.seriesCat).uppercase()
        val shouldEnrich = ui.seriesDubbedFilter ||
            activeLang == Langs.HI ||
            ui.seriesCat == "$LANG${Langs.HI}" ||
            "HINDI" in catName || "INDIA" in catName || "ASIA" in catName || "DUB" in catName ||
            "KIDS" in catName || "ANIME" in catName
        if (shouldEnrich) app.enricher.enqueueVisibleSeries(items)
    }

    var focusedHero by remember { mutableStateOf<HeroFocus?>(null) }
    val firstHero = remember(list.items?.firstOrNull()?.id, catalog.langVersion) {
        list.items?.firstOrNull()?.let { sr ->
            HeroFocus.Title(sr.name, sr.cover, app.suggestions.ref(true, sr.id))
        }
    }

    Browser(
        cats = cats,
        selected = ui.seriesCat,
        onSelect = { ui.seriesCat = it },
        counts = { id -> catalog.seriesByCat[id]?.size },
        gridState = ui.seriesGrid,
        items = list.items,
        itemKey = { it.id },
        stale = list.stale,
        empty = when {
            catalog.loading && catalog.series.isEmpty() -> "Loading series…"
            activeLang != null || activeKind != null || ui.seriesDubbedFilter -> "No series match these filters."
            ui.seriesCat == UiState.FAV -> "Series you add to My list show up here."
            else -> "Nothing here."
        },
        pinnedIds = remember(s.pinnedSeries) { s.pinnedSeries.toSet() },
        langChips = if (mixedView) Langs.viewOrder(langOrder) else emptyList(),
        activeLang = activeLang,
        onLangSelect = { ui.seriesLangFilter = if (activeLang == it) null else it },
        dubbedOnly = ui.seriesDubbedFilter,
        onDubbedToggle = { ui.seriesDubbedFilter = !ui.seriesDubbedFilter },
        kindChips = availableKinds,
        activeKind = activeKind,
        onKindSelect = { ui.seriesKindFilter = if (activeKind == it) null else it },
        hero = focusedHero ?: firstHero,
        pickerTitle = "Series categories",
    ) { sr ->
        val rt = posterScore("s", sr.id)
        val tp = remember(sr.name, sr.year) { TitleParts.parse(sr.name, sr.year) }
        val sub = remember(tp, sr.rating) { listOfNotNull(tp.year, tp.region, sr.rating.takeIf { it > 0 }?.let { "%.1f/10".format(it) }).joinToString(" · ") }
        PosterCard(
            sr.name, sr.cover, { nav.push(Route.SeriesDetail(sr)) }, key = "sr:${sr.id}", titleParts = tp,
            subtitle = sub,
            badge = rt?.first, badgeColor = rt?.second ?: Palette.accent,
            onFocused = { focusedHero = HeroFocus.Title(sr.name, sr.cover, app.suggestions.ref(true, sr.id)) },
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
    langFilter: String? = null,
    kindFilter: String? = null,
    dubbedOnly: Boolean = false,
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
        langFilter = langFilter,
        kindFilter = kindFilter,
        dubbedOnly = dubbedOnly,
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

private val PRESET_IDS = setOf(UiState.RECENT, UiState.TOP, UiState.FAV, UiState.ALL)

@Composable
private fun <T> Browser(
    cats: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
    counts: (String) -> Int?,
    gridState: LazyGridState,
    items: List<T>?,
    /** Stable id of a title, for keying grid cells. Lists from [buildTitleList] hold each id once. */
    itemKey: (T) -> Any,
    /** [items] belong to the previous category and are being replaced: shown dimmed, not selectable. */
    stale: Boolean = false,
    empty: String,
    pinnedIds: Set<String>,
    langChips: List<String> = emptyList(),
    activeLang: String? = null,
    onLangSelect: (String) -> Unit = {},
    dubbedOnly: Boolean = false,
    onDubbedToggle: () -> Unit = {},
    kindChips: List<String> = emptyList(),
    activeKind: String? = null,
    onKindSelect: (String) -> Unit = {},
    hero: HeroFocus? = null,
    pickerTitle: String = "Categories",
    card: @Composable (T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    val selectedObj = remember(cats, selected) { cats.firstOrNull { it.id == selected } }
    val inPickerCat = selected !in PRESET_IDS
    val pickerChipLabel = if (inPickerCat && selectedObj != null) {
        val nm = if (selectedObj.id.startsWith("__")) selectedObj.name else ProviderNames.clean(selectedObj.name)
        "$nm ▾"
    } else {
        "Categories ▾"
    }
    val hasQuickFilters = langChips.isNotEmpty() || kindChips.isNotEmpty()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 760.dp
        val side = if (narrow) 16.dp else 32.dp
        val showHero = maxHeight >= 460.dp && hero != null
        val heroHeight = (maxHeight * 0.27f).coerceIn(136.dp, 210.dp)

        Column(Modifier.fillMaxSize()) {
            // Row 1: Unified top browse bar matching Home (presets + Categories modal).
            LazyRow(
                contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(specials(), key = { "sp:${it.id}" }) { sp ->
                    Chip(sp.name, selected == sp.id, "sp:${sp.id}") {
                        onSelect(sp.id)
                        scope.launch { gridState.scrollToItem(0) }
                    }
                }
                item(key = "sp:cats") {
                    Chip(pickerChipLabel, inPickerCat, "sp:cats") { pickerOpen = true }
                }
            }

            // Row 2: Quick filter chips (Languages, Dubbed, Genres).
            LazyRow(
                contentPadding = PaddingValues(start = side, end = side, top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasQuickFilters || langChips.isEmpty()) {
                    items(langChips, key = { "lf:$it" }) { lang ->
                        Chip(Langs.label(lang), activeLang == lang, "lf:$lang") {
                            onLangSelect(lang)
                            scope.launch { gridState.scrollToItem(0) }
                        }
                    }
                    item(key = "df:dubbed") {
                        Chip("Dubbed", dubbedOnly, "df:dubbed") {
                            onDubbedToggle()
                            scope.launch { gridState.scrollToItem(0) }
                        }
                    }
                    items(kindChips, key = { "kf:$it" }) { kind ->
                        Chip(ContentKinds.label(kind), activeKind == kind, "kf:$kind") {
                            onKindSelect(kind)
                            scope.launch { gridState.scrollToItem(0) }
                        }
                    }
                }
            }

            // Compact focused Hero strip matching Home.
            if (showHero) {
                HomeHero(hero, heroHeight, compact = true)
            }

            // Full-width content grid beside the TV sidebar.
            if (items == null || (stale && items.isEmpty())) {
                EmptyState("Loading…", Modifier.weight(1f))
            } else if (items.isEmpty()) {
                EmptyState(empty, Modifier.weight(1f))
            } else {
                val enabled = LocalFocusEnabled.current
                CompositionLocalProvider(LocalFocusEnabled provides (enabled && !stale)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (stale) 0.35f else 1f).fadeEdges(),
                        state = gridState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, start = side, end = side),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(items, key = itemKey) { card(it) }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        val first = remember { FocusRequester() }
        InitialFocus(first)
        TvDialog({ pickerOpen = false }, width = 560.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(pickerTitle, fontSize = Type.heading, fontWeight = FontWeight.Bold, color = Palette.text)
                PillButton("Close", { pickerOpen = false }, focusRequester = first, key = "catdlg:close", compact = true)
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(cats, key = { "dlg:${it.id}" }) { c ->
                    ListRow(
                        if (c.id.startsWith("__")) c.name else ProviderNames.clean(c.name),
                        onClick = {
                            onSelect(c.id)
                            pickerOpen = false
                            scope.launch { gridState.scrollToItem(0) }
                        },
                        selected = selected == c.id,
                        key = "catdlg:${c.id}",
                        trailing = counts(c.id)?.toString(),
                        icon = when {
                            c.id == UiState.FAV -> Icons.Filled.Star
                            c.id in pinnedIds -> Icons.Filled.Place
                            c.id.startsWith(LANG) -> RelayIcons.Language
                            c.id == selected -> Icons.Filled.ArrowDropDown
                            else -> null
                        },
                    )
                }
            }
        }
    }
}
