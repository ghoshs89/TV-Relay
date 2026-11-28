package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TitleListTest {
    private fun m(id: Int, name: String, cat: String, added: Long = 0, rating: Double = 0.0) =
        Movie(id, name, null, cat, rating, "mp4", added, null, null)

    private val movies = listOf(
        m(1, "beta", "c1", added = 10, rating = 7.0),
        m(2, "Alpha", "c1", added = 30, rating = 8.5),
        m(3, "alpha", "c2", added = 20, rating = 0.0),
        m(4, "Zed", "adult", added = 50, rating = 6.0),
        m(5, "Árbol", "c2", added = 40, rating = 9.0),
        m(6, "gamma", "hid", added = 5, rating = 5.0),
    )
    private val langs = mapOf(1 to "hi", 2 to "en", 3 to "hi", 4 to "en", 5 to "es", 6 to "hi")

    // The lists as they were built before: sorted in the library, then ranked with sortedBy.
    private val byAdded = movies.sortedByDescending { it.added }
    private val byRating = movies.filter { it.rating > 0 }.sortedByDescending { it.rating }
    private val byName = sortedByLowerName(movies) { it.name }
    private val byCat = movies.groupBy { it.categoryId }

    private val src = object : TitleSource<Movie> {
        override val byAdded = this@TitleListTest.byAdded
        override val byRating = this@TitleListTest.byRating
        override val byName = this@TitleListTest.byName
        override fun inCategory(cat: String) = byCat[cat].orEmpty()
        override fun byId(id: Int) = movies.firstOrNull { it.id == id }
        override fun lang(id: Int) = langs[id]
        override fun id(t: Movie) = t.id
        override fun category(t: Movie) = t.categoryId
    }

    private fun query(sel: TitleSelection, blocked: Set<String> = emptySet(), hidden: Set<String> = emptySet(), order: List<String> = listOf("hi", "en")) =
        TitleQuery(sel, blocked, hidden, order)

    private fun ids(l: List<Movie>) = l.map { it.id }

    /** The old implementation, written out in full. */
    private fun old(cat: String, favIds: List<Int>, restrict: Boolean, hidden: Set<String>, order: List<String>): List<Movie> {
        val rank = LangRank(order)
        val adult = setOf("adult")
        val base = when {
            cat == "recent" -> byAdded
            cat == "top" -> byRating
            cat == "fav" -> favIds.mapNotNull { id -> movies.firstOrNull { it.id == id } }
            cat == "all" -> movies.sortedBy { it.name.lowercase() }
            cat.startsWith("lang:") -> byAdded.filter { langs[it.id] == cat.removePrefix("lang:") }
            else -> byCat[cat].orEmpty()
        }
        val allowed = base.filter { !restrict || it.categoryId !in adult }
        return if (cat == "recent" || cat == "top" || cat == "all") {
            allowed.filter { "vod:${it.categoryId}" !in hidden }.sortedBy { rank.rank(langs[it.id]) }
        } else allowed
    }

    @Test
    fun everyListMatchesTheOldImplementation() {
        val hiddenKeys = setOf("vod:hid")
        for (restrict in listOf(false, true)) {
            for (order in listOf(listOf("hi", "en"), listOf("en"), emptyList())) {
                val blocked = if (restrict) setOf("adult") else emptySet()
                val hidden = setOf("hid")
                val cases = listOf(
                    "recent" to TitleSelection.Recent, "top" to TitleSelection.Top, "all" to TitleSelection.All,
                    "fav" to TitleSelection.Favourites(listOf(5, 99, 2)),
                    "lang:hi" to TitleSelection.Language("hi"),
                    "c1" to TitleSelection.Category("c1"), "adult" to TitleSelection.Category("adult"),
                    "missing" to TitleSelection.Category("missing"),
                )
                for ((cat, sel) in cases) {
                    val want = old(cat, listOf(5, 99, 2), restrict, hiddenKeys, order)
                    val got = buildTitleList(src, query(sel, blocked, hidden, order))
                    assertEquals("$cat restrict=$restrict order=$order", ids(want), ids(got))
                }
            }
        }
    }

    @Test
    fun allAToZIgnoresCaseAndKeepsTiesInLibraryOrder() {
        // "Alpha" (2) and "alpha" (3) tie; the library order decides.
        assertEquals(listOf(2, 3), ids(byName).take(2))
        assertEquals(movies.sortedBy { it.name.lowercase() }, byName)
    }

    @Test
    fun mixedListsPutYourLanguagesFirstWithoutDroppingAnything() {
        val got = buildTitleList(src, query(TitleSelection.Recent, order = listOf("hi", "en")))
        // hi titles by recency (3, 1, 6), then en (4, 2), then the unranked language (5).
        // 6 is hidden only when its category is in hiddenCats.
        assertEquals(listOf(3, 1, 6, 4, 2, 5), ids(got))
        val hidden = buildTitleList(src, query(TitleSelection.Recent, hidden = setOf("hid")))
        assertEquals(listOf(3, 1, 4, 2, 5), ids(hidden))
    }

    @Test
    fun categoryListsAreNotReorderedOrHiddenFiltered() {
        val got = buildTitleList(src, query(TitleSelection.Category("hid"), hidden = setOf("hid")))
        assertEquals(listOf(6), ids(got))
    }

    @Test
    fun cacheReturnsWhatWasPutUntilTheGenerationChanges() {
        val cache = TitleListCache<Movie>(max = 2)
        val q1 = query(TitleSelection.Recent)
        val q2 = query(TitleSelection.Top)
        val q3 = query(TitleSelection.All)
        val l1 = listOf(movies[0])
        assertNull(cache.get(1, q1))
        cache.put(1, q1, l1)
        assertSame(l1, cache.get(1, q1))
        assertNull("another generation", cache.get(2, q1))
        cache.put(1, q2, emptyList())
        cache.get(1, q1) // q1 is now the most recently used
        cache.put(1, q3, emptyList())
        assertNull("least recently used was dropped", cache.get(1, q2))
        assertSame(l1, cache.get(1, q1))
        cache.put(2, q1, emptyList())
        assertNull("old generation cleared", cache.get(2, q3))
    }

    @Test
    fun eachTitleIsListedOnceEvenWhenTheSourceRepeatsIt() {
        // A favourite saved twice.
        assertEquals(listOf(5, 2), ids(buildTitleList(src, query(TitleSelection.Favourites(listOf(5, 5, 2, 5))))))
        // A provider listing the same id twice in a mixed list: the first one stays, order is kept.
        val repeated = object : TitleSource<Movie> by src {
            override val byAdded = this@TitleListTest.byAdded + this@TitleListTest.byAdded.take(2)
        }
        val got = ids(buildTitleList(repeated, query(TitleSelection.Recent)))
        assertEquals(got.distinct(), got)
        assertEquals(ids(buildTitleList(src, query(TitleSelection.Recent))), got)
    }

    @Test
    fun aListWithoutRepeatsIsReturnedAsItIs() {
        val sel = TitleSelection.Category("c1")
        assertSame(src.inCategory("c1"), buildTitleList(src, query(sel)))
    }

    @Test
    fun languageAndContentKindFiltersNarrowMixedLists() {
        val kinds = mapOf("c1" to ContentKinds.ACTION, "c2" to ContentKinds.DOCS)
        val withKinds = object : TitleSource<Movie> by src {
            override fun kind(t: Movie) = kinds[t.categoryId]
        }
        val enOnly = TitleQuery(TitleSelection.Recent, blockedCats = setOf("adult"), hiddenCats = setOf("hid"), langOrder = listOf("hi", "en"), langFilter = "en")
        assertEquals(listOf(2), ids(buildTitleList(withKinds, enOnly)))

        val docsOnly = TitleQuery(TitleSelection.Recent, blockedCats = setOf("adult"), hiddenCats = setOf("hid"), langOrder = listOf("hi", "en"), kindFilter = ContentKinds.DOCS)
        assertEquals(listOf(3, 5), ids(buildTitleList(withKinds, docsOnly)))
    }
}
