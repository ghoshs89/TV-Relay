package dev.relay.tv.data

/** What a Movies or Series list is showing. Carries the ids it needs so equal queries mean equal lists. */
sealed interface TitleSelection {
    data object Recent : TitleSelection
    data object Top : TitleSelection
    data object All : TitleSelection
    data class Favourites(val ids: List<Int>) : TitleSelection
    data class Language(val key: String) : TitleSelection
    data class Category(val id: String) : TitleSelection
}

/**
 * Everything that decides which titles a Movies or Series list shows and in what order. A data
 * class so that unrelated changes (progress saved, a favourite elsewhere) leave it equal and the
 * list is not built again.
 */
data class TitleQuery(
    val selection: TitleSelection,
    /** Categories this profile can't see (adult, when restricted). */
    val blockedCats: Set<String>,
    /** Categories hidden in settings (ids for this kind only). Applied to the mixed lists. */
    val hiddenCats: Set<String>,
    val langOrder: List<String>,
    /** Optional language filter chip on mixed lists ("en", "hi"...). */
    val langFilter: String? = null,
    /** Optional content-type filter chip ("sports", "docs", "comedy"...). */
    val kindFilter: String? = null,
    /** Filter to dubbed titles (e.g. Hindi dubbed Hollywood/South films). */
    val dubbedOnly: Boolean = false,
)

/** What [buildTitleList] reads from the library. Implemented by [Catalog] for movies and for series; fakes in tests. */
interface TitleSource<T> {
    val byAdded: List<T>
    val byRating: List<T>
    val byName: List<T>
    fun inCategory(cat: String): List<T>
    fun byId(id: Int): T?
    fun lang(id: Int): String?
    fun kind(t: T): String? = null
    fun apiGenres(t: T): List<String> = emptyList()
    fun isDubbed(t: T): Boolean = false
    fun id(t: T): Int
    fun category(t: T): String
}

/**
 * Builds a Movies or Series list. Runs off the screen thread. The mixed lists (Recently added,
 * Top rated, All A to Z) leave out hidden categories and put your languages first; the others
 * are shown as they are, minus categories this profile can't see.
 */
fun <T> buildTitleList(src: TitleSource<T>, q: TitleQuery, check: () -> Unit = {}): List<T> {
    val base: List<T> = when (val s = q.selection) {
        TitleSelection.Recent -> src.byAdded
        TitleSelection.Top -> src.byRating
        TitleSelection.All -> src.byName
        is TitleSelection.Favourites -> s.ids.mapNotNull { src.byId(it) }
        is TitleSelection.Language -> src.byAdded.filterChecked(check) { src.lang(src.id(it)) == s.key }
        is TitleSelection.Category -> src.inCategory(s.id)
    }
    var allowed = if (q.blockedCats.isEmpty()) base else base.filterChecked(check) { src.category(it) !in q.blockedCats }
    if (q.langFilter != null) allowed = allowed.filterChecked(check) { src.lang(src.id(it)) == q.langFilter }
    if (q.kindFilter != null) {
        allowed = allowed.filterChecked(check) {
            src.kind(it) == q.kindFilter || ContentKinds.matchesApiGenres(src.apiGenres(it), q.kindFilter)
        }
    }
    if (q.dubbedOnly) allowed = allowed.filterChecked(check) { src.isDubbed(it) }
    val mixed = q.selection == TitleSelection.Recent || q.selection == TitleSelection.Top || q.selection == TitleSelection.All
    val ranked = if (!mixed) {
        allowed
    } else {
        val visible = if (q.hiddenCats.isEmpty()) allowed else allowed.filterChecked(check) { src.category(it) !in q.hiddenCats }
        if (q.langFilter != null) visible
        else {
            val rank = LangRank(q.langOrder)
            rankSorted(visible, q.langOrder.size + 1, check) { rank.rank(src.lang(src.id(it))) }
        }
    }
    // Each title once (a provider can list an id twice, a favourite can be saved twice), so grids can key their cells by id.
    return uniqueById(ranked, src)
}

private fun <T> uniqueById(list: List<T>, src: TitleSource<T>): List<T> {
    val seen = HashSet<Int>(list.size * 2)
    var clean = true
    for (t in list) if (!seen.add(src.id(t))) { clean = false; break }
    if (clean) return list
    seen.clear()
    return list.filter { seen.add(src.id(it)) }
}

/** Stable sort by name that lower-cases each name once instead of twice per comparison. */
fun <T> sortedByLowerName(list: List<T>, name: (T) -> String): List<T> =
    list.mapTo(ArrayList(list.size)) { it to name(it).lowercase() }.sortedBy { it.second }.map { it.first }

/**
 * The last few lists that were built, so going back to a category is instant. Everything is
 * dropped when the library or the language guesses change ([generation]).
 */
class TitleListCache<T>(private val max: Int = 6) {
    private var generation = Long.MIN_VALUE
    private val map = object : LinkedHashMap<TitleQuery, List<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TitleQuery, List<T>>): Boolean = size > max
    }

    @Synchronized
    fun get(generation: Long, q: TitleQuery): List<T>? = if (generation == this.generation) map[q] else null

    @Synchronized
    fun put(generation: Long, q: TitleQuery, items: List<T>) {
        if (generation != this.generation) {
            map.clear()
            this.generation = generation
        }
        map[q] = items
    }
}
