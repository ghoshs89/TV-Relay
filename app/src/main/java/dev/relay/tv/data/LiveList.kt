package dev.relay.tv.data

/** Filter names shared by the Live TV chips and [buildLiveList]. */
object LiveFilters {
    const val CATCHUP = "catchup"
    const val HD = "hd"
    const val GUIDE = "guide"
}

/** What the Live TV list is showing. Carries the ids it needs so equal queries mean equal lists. */
sealed interface LiveSelection {
    data class Favourites(val ids: List<Int>) : LiveSelection
    data class History(val ids: List<Int>) : LiveSelection
    data object All : LiveSelection
    data class Language(val key: String) : LiveSelection
    data class Group(val ids: List<Int>) : LiveSelection
    data class Category(val id: String) : LiveSelection
}

/**
 * Everything that decides which channels Live TV lists and in what order. It is a data class on
 * purpose: when something unrelated changes (a favourite elsewhere, history written by the
 * player) the query stays equal and the list isn't rebuilt.
 */
data class LiveQuery(
    /** The screen's own name for the category; used to tell whether a result is for what's selected. */
    val cat: String,
    val selection: LiveSelection,
    val sort: Int,
    val filters: Set<String>,
    val find: String,
    /** Categories this profile can't see (adult, when restricted). */
    val blockedCats: Set<String>,
    val hiddenCats: Set<String>,
    val langOrder: List<String>,
    /** Show several versions of a channel once. */
    val merge: Boolean,
    /** Favourite channel ids; only filled when the sort or the list needs them. */
    val favIds: List<Int>,
    val preferred: Map<String, Int>,
)

class LiveResult(val query: LiveQuery, val baseSize: Int, val channels: List<Channel>)

/** What [buildLiveList] reads from the library. Implemented by [Catalog]; fakes in tests. */
interface LiveData {
    val channels: List<Channel>
    fun byId(id: Int): Channel?
    fun inCategory(cat: String): List<Channel>
    fun lang(id: Int): String?
    fun sortKey(ch: Channel): String
    fun isHd(ch: Channel): Boolean
    fun hasGuide(ch: Channel): Boolean
    fun collapse(list: List<Channel>, preferred: Map<String, Int>): List<Channel>
}

/** Stable sort by a small integer rank, in one pass instead of one comparison per pair. */
internal fun <T> rankSorted(list: List<T>, maxRank: Int, check: () -> Unit = {}, rank: (T) -> Int): List<T> {
    val buckets = arrayOfNulls<ArrayList<T>>(maxRank + 1)
    var n = 0
    for (x in list) {
        if (++n % CHECK_EVERY == 0) check()
        val r = rank(x).coerceIn(0, maxRank)
        (buckets[r] ?: ArrayList<T>().also { buckets[r] = it }).add(x)
    }
    val out = ArrayList<T>(list.size)
    for (b in buckets) if (b != null) out.addAll(b)
    return out
}

private class Keyed(val ch: Channel, val key: String)

/**
 * Builds the channel list for Live TV. Runs off the screen thread; reads only [data] and [q].
 * The result has each channel once, so lists can key their rows by channel id. [check] is called
 * every couple of thousand channels and may throw to stop a build that is no longer wanted.
 */
fun buildLiveList(data: LiveData, q: LiveQuery, check: () -> Unit = {}): LiveResult {
    val rank = LangRank(q.langOrder)
    fun shown(ch: Channel) = ch.categoryId !in q.blockedCats && ch.categoryId !in q.hiddenCats
    val maxRank = q.langOrder.size + 1

    val base: List<Channel> = when (val s = q.selection) {
        is LiveSelection.Favourites -> s.ids.mapNotNull { data.byId(it) }
        is LiveSelection.History -> s.ids.mapNotNull { data.byId(it) }
        LiveSelection.All -> rankSorted(data.channels.filterChecked(check) { shown(it) }, maxRank, check) { rank.rank(data.lang(it.id)) }
        is LiveSelection.Language -> data.channels.filterChecked(check) { data.lang(it.id) == s.key && shown(it) }
        is LiveSelection.Group -> s.ids.mapNotNull { data.byId(it) }.filter { it.categoryId !in q.blockedCats }
        is LiveSelection.Category -> data.inCategory(s.id)
    }

    var list: List<Channel> = base
    val f = q.filters
    if (LiveFilters.CATCHUP in f) list = list.filterChecked(check) { it.hasArchive }
    if (LiveFilters.HD in f) list = list.filterChecked(check) { data.isHd(it) }
    if (LiveFilters.GUIDE in f) list = list.filterChecked(check) { !it.epgId.isNullOrBlank() || data.hasGuide(it) }
    val text = q.find.trim().lowercase()
    if (text.isNotEmpty()) {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        list = list.filterChecked(check) { ch -> val n = ch.name.lowercase(); tokens.all { n.contains(it) } }
    }

    list = when (q.sort) {
        1 -> list.mapCheckedTo(ArrayList(list.size), check) { Keyed(it, data.sortKey(it)) }
            .let { keyed -> check(); keyed }
            .sortedWith(compareBy<Keyed>({ if (it.ch.num > 0) 0 else 1 }, { it.ch.num }, { it.key }))
            .map { it.ch }
        2 -> list.mapCheckedTo(ArrayList(list.size), check) { Keyed(it, data.sortKey(it)) }.sortedBy { it.key }.map { it.ch }
        3 -> {
            val fav = q.favIds.toHashSet()
            val (first, rest) = list.partition { it.id in fav }
            first + rest
        }
        else -> list
    }

    check()
    val out = if (q.merge) data.collapse(list, q.preferred) else list
    check()
    return LiveResult(q, base.size, out.distinctBy { it.id })
}
