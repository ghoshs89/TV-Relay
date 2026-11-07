package dev.relay.tv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.relay.tv.data.Movie
import dev.relay.tv.data.Profile
import dev.relay.tv.data.Series
import dev.relay.tv.data.Settings

class Session {
    var profile by mutableStateOf<Profile?>(null)
    var autoPickDone = false

    // Once per app start.
    var expiryChecked = false
    var autoStartDone = false
    val profileId: String get() = profile?.id ?: "default"
    fun restrict(settings: Settings): Boolean = profile?.kids == true || settings.hideAdult

    /** This profile's language order, or the household default. */
    fun langOrder(settings: Settings): List<String> = profile?.langOrder ?: settings.langOrder

    /** Languages with their own Home rows ("New in Hindi"...). */
    fun langFeatured(settings: Settings): List<String> = profile?.langFeatured ?: settings.langFeatured

    fun langRank(settings: Settings): dev.relay.tv.data.LangRank = dev.relay.tv.data.LangRank(langOrder(settings))
}

/** Browsing state that survives opening a detail page and coming back. */
class UiState {
    var tab by mutableIntStateOf(0)
    var liveCat by mutableStateOf(ALL)
    var movieCat by mutableStateOf(RECENT)
    var seriesCat by mutableStateOf(RECENT)
    var query by mutableStateOf("")

    // Live TV browsing
    var liveRestored = false
    var liveCountry by mutableStateOf<String?>(null)
    var liveFilters by mutableStateOf(emptySet<String>())
    var liveFind by mutableStateOf("")
    var liveGrid by mutableStateOf(false)

    val liveCatList = LazyListState()
    val liveChList = LazyListState()
    val movieCatList = LazyListState()
    val movieGrid = LazyGridState()
    val seriesCatList = LazyListState()
    val seriesGrid = LazyGridState()
    val homeList = LazyListState()

    val focus = FocusMemory()

    companion object {
        const val ALL = "__all"
        const val FAV = "__fav"
        const val RECENT = "__recent"
        const val TOP = "__top"
        const val HISTORY = "__history"
    }
}

/**
 * Where focus was on one screen. Each screen in the navigation stack has its own, so a detail
 * page's buttons can't overwrite the position Home returns to.
 */
class FocusMemory {
    var lastKey: String? = null
    var restoreTick by mutableIntStateOf(0)
}

sealed interface Route {
    data object Login : Route
    data object Profiles : Route
    data object Home : Route
    data class MovieDetail(val movie: Movie) : Route
    data class SeriesDetail(val series: Series) : Route
    data object Setup : Route
}

class Nav(start: Route) {
    val stack = mutableStateListOf(start)
    private val memories = mutableListOf(FocusMemory())

    /** Focus memory for the screen at [index]; Home uses [home] so it survives profile changes. */
    fun memory(index: Int, home: FocusMemory): FocusMemory =
        if (stack.getOrNull(index) == Route.Home) home else memories.getOrElse(index) { FocusMemory() }

    fun push(r: Route) {
        dev.relay.tv.data.AppLog.d("Nav", "Open ${r::class.simpleName}")
        stack.add(r)
        memories.add(FocusMemory())
    }

    /** Closes the top screen and asks the one underneath to put focus back where it was. */
    fun pop(home: FocusMemory) {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            memories.removeAt(memories.lastIndex)
            memory(stack.lastIndex, home).restoreTick++
        }
    }

    fun reset(r: Route) {
        stack.clear()
        stack.add(r)
        memories.clear()
        memories.add(FocusMemory())
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }
