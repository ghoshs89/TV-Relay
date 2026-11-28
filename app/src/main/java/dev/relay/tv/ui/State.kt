package dev.relay.tv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.relay.tv.data.Movie
import dev.relay.tv.data.Profile
import dev.relay.tv.data.ProfileSwitchLock
import dev.relay.tv.data.Store
import dev.relay.tv.data.Series
import dev.relay.tv.data.Settings

class Session {
    var profile by mutableStateOf<Profile?>(null)
    var autoPickDone = false

    // Once per app start.
    var expiryChecked = false
    var autoStartDone = false
    val profileId: String get() = profile?.id ?: "default"
    fun profileSwitchLock(store: Store): ProfileSwitchLock? {
        val selectedId = profile?.id ?: store.lastProfileId
        val selected = store.profiles.firstOrNull { it.id == selectedId }
        return ProfileSwitchLock.forProfile(selected, store.parentPin)
    }

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
    var liveKind by mutableStateOf<String?>(null)
    var liveFind by mutableStateOf("")
    var liveGrid by mutableStateOf(false)

    // Movies and Series quick filters
    var movieLangFilter by mutableStateOf<String?>(null)
    var movieKindFilter by mutableStateOf<String?>(null)
    var seriesLangFilter by mutableStateOf<String?>(null)
    var seriesKindFilter by mutableStateOf<String?>(null)

    val liveCatList = LazyListState()
    val liveChList = LazyListState()

    /** The last Live TV list that was built; shown again at once when the tab is reopened. */
    var liveResult by mutableStateOf<dev.relay.tv.data.LiveResult?>(null)

    /** Recently built Movies and Series lists, so going back to a category is instant. */
    val movieLists = dev.relay.tv.data.TitleListCache<dev.relay.tv.data.Movie>()
    val seriesLists = dev.relay.tv.data.TitleListCache<dev.relay.tv.data.Series>()
    val movieCatList = LazyListState()
    val movieGrid = LazyGridState()
    val seriesCatList = LazyListState()
    val seriesGrid = LazyGridState()
    val homeList = LazyListState()

    /** "New in <language>" rows on Home: what each was built from, and the row. Main thread only. */
    val homeLangRows = HashMap<String, Pair<List<Any>, List<HomeTitle>>>()

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

    // Items on screen that have a key, by key, in composition order. Main thread only.
    private val requesters = LinkedHashMap<String, FocusRequester>()

    fun register(key: String, requester: FocusRequester) {
        requesters[key] = requester
    }

    fun unregister(key: String, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
    }

    /** Asks the remembered item to take focus. False when it isn't on screen (yet). */
    fun restore(): Boolean {
        val requester = lastKey?.let { requesters[it] } ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }

    /** Focuses the remembered item, or the first registered content item in composition order. */
    fun restoreOrFirst(): Boolean {
        if (restore()) return true
        for (requester in requesters.values) {
            if (runCatching { requester.requestFocus() }.isSuccess) return true
        }
        return false
    }
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
    var profileSwitchApproval by mutableStateOf<ProfileSwitchLock?>(null)
        private set

    fun openProfilePicker(approval: ProfileSwitchLock?) {
        reset(Route.Profiles)
        profileSwitchApproval = approval
    }

    fun cancelProfileSwitch() {
        profileSwitchApproval = null
    }

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
        profileSwitchApproval = null
        stack.clear()
        stack.add(r)
        memories.clear()
        memories.add(FocusMemory())
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }
