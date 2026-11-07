package dev.relay.tv.data

/** Rows Home can show, per profile: which ones and in what order. */
object HomeLayout {
    const val CONTINUE = "continue"
    const val FAV_CHANNELS = "favChannels"
    const val RECENT_CHANNELS = "recentChannels"
    const val MY_LIST = "myList"
    const val NEW_MOVIES = "newMovies"
    const val NEW_SERIES = "newSeries"
    const val TOP_MOVIES = "topMovies"
    const val TOP_SERIES = "topSeries"
    const val LANG = "lang:"

    private val TAIL = listOf(MY_LIST, NEW_MOVIES, NEW_SERIES, TOP_MOVIES, TOP_SERIES)

    /** Continue watching and your channels, then "New in" rows for featured languages, then the rest. */
    fun defaults(featured: List<String>): List<String> =
        listOf(CONTINUE, FAV_CHANNELS, RECENT_CHANNELS) + featured.map { LANG + it } + TAIL

    /** For live-TV viewers: channels first. */
    fun liveFirst(featured: List<String>): List<String> =
        listOf(FAV_CHANNELS, RECENT_CHANNELS, CONTINUE) + featured.map { LANG + it } + TAIL

    /** Every row this profile could show, visible ones first in their order. */
    fun all(visible: List<String>, featured: List<String>): List<String> =
        (visible + defaults(featured) + Langs.GROUPS.map { LANG + it }).distinct()

    /** The profile's rows, or the defaults. Language rows for languages no longer known are dropped. */
    fun resolve(saved: List<String>?, featured: List<String>): List<String> =
        (saved ?: defaults(featured)).filter { !it.startsWith(LANG) || it.removePrefix(LANG) in Langs.GROUPS }

    fun label(id: String): String = when (id) {
        CONTINUE -> "Continue watching"
        FAV_CHANNELS -> "Favourite channels"
        RECENT_CHANNELS -> "Recently watched channels"
        MY_LIST -> "My list"
        NEW_MOVIES -> "Recently added movies"
        NEW_SERIES -> "Recently updated series"
        TOP_MOVIES -> "Top rated movies"
        TOP_SERIES -> "Top rated series"
        else -> if (id.startsWith(LANG)) "New in ${Langs.label(id.removePrefix(LANG))}" else id
    }

    /** Moves [id] one place up (-1) or down (+1) among the visible rows. */
    fun move(rows: List<String>, id: String, by: Int): List<String> {
        val i = rows.indexOf(id)
        val j = i + by
        if (i < 0 || j !in rows.indices) return rows
        return rows.toMutableList().also { it[i] = rows[j]; it[j] = id }
    }

    fun toggle(rows: List<String>, id: String): List<String> = if (id in rows) rows - id else rows + id
}

/** Moving items in an ordered list, shared by the language and Home editors. */
fun <T> List<T>.moved(item: T, by: Int): List<T> {
    val i = indexOf(item)
    val j = i + by
    if (i < 0 || j !in indices) return this
    return toMutableList().also { it[i] = this[j]; it[j] = item }
}
