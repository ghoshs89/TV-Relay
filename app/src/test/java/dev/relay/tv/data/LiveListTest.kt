package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveListTest {
    private fun ch(id: Int, num: Int, name: String, cat: String, archive: Int = 0, epg: String? = null) =
        Channel(id, num, name, null, cat, epg, archive)

    private val all = listOf(
        ch(1, 0, "UK: Sky News HD", "uk", epg = "sky"),
        ch(2, 5, "IN: Zee TV", "in", archive = 3),
        ch(3, 2, "IN: Star Plus FHD", "in", epg = "star"),
        ch(4, 0, "Adult One", "xxx"),
        ch(5, 9, "CA: CBC", "ca"),
        ch(6, 0, "IN: Zee TV HD", "in"),
    )
    private val langs = mapOf(1 to "en", 2 to "hi", 3 to "hi", 5 to "en", 6 to "hi")

    private val data = object : LiveData {
        override val channels = all
        override fun byId(id: Int) = all.firstOrNull { it.id == id }
        override fun inCategory(cat: String) = all.filter { it.categoryId == cat }
        override fun lang(id: Int) = langs[id]
        override fun sortKey(ch: Channel) = ChannelKeys.sortKey(ch.name)
        override fun isHd(ch: Channel) = ChannelKeys.isHd(ch.name)
        override fun hasGuide(ch: Channel) = ch.id == 6
        // Pretend 2 and 6 are two versions of one channel; the best (6, HD) is shown.
        override fun collapse(list: List<Channel>, preferred: Map<String, Int>) =
            if (list.any { it.id == 6 }) list.filter { it.id != 2 } else list
    }

    private fun query(
        selection: LiveSelection = LiveSelection.All,
        cat: String = "all",
        sort: Int = 0,
        filters: Set<String> = emptySet(),
        find: String = "",
        blocked: Set<String> = setOf("xxx"),
        hidden: Set<String> = emptySet(),
        order: List<String> = listOf("en", "hi"),
        merge: Boolean = false,
        favs: List<Int> = emptyList(),
    ) = LiveQuery(cat, selection, sort, filters, find, blocked, hidden, order, merge, favs, emptyMap())

    private fun ids(q: LiveQuery) = buildLiveList(data, q).channels.map { it.id }

    @Test
    fun allChannelsHidesBlockedAndPutsYourLanguagesFirst() {
        // en first (1, 5), then hi (2, 3, 6); adult (4) is out; unknown languages would go last.
        assertEquals(listOf(1, 5, 2, 3, 6), ids(query()))
    }

    @Test
    fun hiddenCategoriesAreLeftOut() {
        assertEquals(listOf(1, 5), ids(query(hidden = setOf("in"))))
    }

    @Test
    fun noBlockingShowsEverything() {
        assertTrue(4 in ids(query(blocked = emptySet())))
    }

    @Test
    fun languageAndCategoryAndFavourites() {
        assertEquals(listOf(2, 3, 6), ids(query(LiveSelection.Language("hi"))))
        assertEquals(listOf(2, 3, 6), ids(query(LiveSelection.Category("in"))))
        assertEquals(listOf(5, 1), ids(query(LiveSelection.Favourites(listOf(5, 1, 99)))))
        assertEquals(listOf(3, 2), ids(query(LiveSelection.History(listOf(3, 2)))))
    }

    @Test
    fun groupDropsBlockedChannels() {
        assertEquals(listOf(5, 1), ids(query(LiveSelection.Group(listOf(5, 4, 1)))))
    }

    @Test
    fun filters() {
        assertEquals(listOf(2), ids(query(filters = setOf(LiveFilters.CATCHUP))))
        assertEquals(listOf(1, 3, 6), ids(query(filters = setOf(LiveFilters.HD))))
        // Has guide: an epg id, or guide data from the external guide (6).
        assertEquals(listOf(1, 3, 6), ids(query(filters = setOf(LiveFilters.GUIDE))))
        assertEquals(listOf(3), ids(query(filters = setOf(LiveFilters.HD, LiveFilters.GUIDE), find = "star")))
    }

    @Test
    fun findMatchesEveryWord() {
        assertEquals(listOf(2, 6), ids(query(find = "zee")))
        assertEquals(listOf(6), ids(query(find = "zee hd")))
        assertEquals(emptyList<Int>(), ids(query(find = "nothing")))
    }

    @Test
    fun sorts() {
        // Number: numbered first by number, then the rest by name.
        assertEquals(listOf(3, 2, 5, 1, 6), ids(query(sort = 1)))
        // A to Z ignores the country tag and case: cbc, skynews hd, starplus fhd, zee tv, zee tv hd.
        assertEquals(listOf(5, 1, 3, 2, 6), ids(query(sort = 2)))
        // Favourites first keeps the rest in order.
        assertEquals(listOf(6, 1, 5, 2, 3), ids(query(sort = 3, favs = listOf(6))))
    }

    @Test
    fun mergeShowsEachChannelOnce() {
        assertEquals(listOf(1, 5, 3, 6), ids(query(merge = true)))
    }

    @Test
    fun resultKeepsEachChannelOnce() {
        val r = buildLiveList(data, query(LiveSelection.Favourites(listOf(1, 1, 5, 1))))
        assertEquals(listOf(1, 5), r.channels.map { it.id })
        assertEquals(4, r.baseSize)
    }

    @Test
    fun equalQueriesAreEqual() {
        assertEquals(query(), query())
        assertTrue(query(find = "a") != query(find = "b"))
    }

    @Test
    fun rankSortIsStable() {
        val sorted = rankSorted(listOf("b1", "a1", "b2", "c1", "a2"), 3) { "abc".indexOf(it[0]) }
        assertEquals(listOf("a1", "a2", "b1", "b2", "c1"), sorted)
        assertEquals(listOf("x"), rankSorted(listOf("x"), 2) { 99 })
    }

    @Test
    fun lettersComeWithAnAToZListAndPointAtTheFirstChannelOfEach() {
        val many = (1..50).map { i -> ch(100 + i, 0, (if (i <= 20) "Alpha" else if (i <= 45) "Bravo" else "9 News") + " $i", "big") }
        val big = object : LiveData by data {
            override val channels = many
            override fun inCategory(cat: String) = many
            override fun byId(id: Int) = many.firstOrNull { it.id == id }
        }
        val r = buildLiveList(big, query(LiveSelection.Category("big"), sort = 2))
        assertEquals(listOf("#", "A", "B"), r.letters.map { it.first })
        for ((letter, index) in r.letters) {
            val first = ChannelKeys.sortKey(r.channels[index].name).first().uppercaseChar()
            assertEquals(letter, if (first.isLetter()) first.toString() else "#")
            if (index > 0) {
                val before = ChannelKeys.sortKey(r.channels[index - 1].name).first().uppercaseChar()
                assertTrue(before != first)
            }
        }
    }

    @Test
    fun noLettersForShortListsOrOtherSorts() {
        assertTrue(buildLiveList(data, query(sort = 2)).letters.isEmpty())
        val many = (1..50).map { i -> ch(100 + i, 0, "Name $i", "big") }
        val big = object : LiveData by data {
            override fun inCategory(cat: String) = many
        }
        assertTrue(buildLiveList(big, query(LiveSelection.Category("big"), sort = 0)).letters.isEmpty())
    }

    @Test
    fun favouritesHistoryAndCategoryDropBlockedAdultChannels() {
        assertEquals(listOf(5, 1), ids(query(LiveSelection.Favourites(listOf(5, 4, 1)))))
        assertEquals(listOf(3, 2), ids(query(LiveSelection.History(listOf(3, 4, 2)))))
        assertEquals(emptyList<Int>(), ids(query(LiveSelection.Category("xxx"))))
    }

    @Test
    fun contentKindFilterSelectsMatchingChannelsOnly() {
        val kinds = mapOf(1 to ContentKinds.NEWS, 3 to ContentKinds.ENTERTAINMENT)
        val withKinds = object : LiveData by data {
            override fun kind(ch: Channel) = kinds[ch.id]
        }
        val q = query().copy(kind = ContentKinds.NEWS)
        assertEquals(listOf(1), buildLiveList(withKinds, q).channels.map { it.id })
    }
}
