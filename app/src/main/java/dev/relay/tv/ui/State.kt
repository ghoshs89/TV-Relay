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
    val profileId: String get() = profile?.id ?: "default"
    fun restrict(settings: Settings): Boolean = profile?.kids == true || settings.hideAdult
}

/** Browsing state that survives opening a detail page and coming back. */
class UiState {
    var tab by mutableIntStateOf(0)
    var liveCat by mutableStateOf(ALL)
    var movieCat by mutableStateOf(RECENT)
    var seriesCat by mutableStateOf(RECENT)
    var query by mutableStateOf("")

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
}

class Nav(start: Route) {
    val stack = mutableStateListOf(start)

    fun push(r: Route) {
        stack.add(r)
    }

    fun pop(memory: FocusMemory) {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            memory.restoreTick++
        }
    }

    fun reset(r: Route) {
        stack.clear()
        stack.add(r)
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }
