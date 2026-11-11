package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A list build that is no longer wanted stops, and one that is wanted gives the same list as before. */
class ListCancelTest {
    private class Stop : RuntimeException()

    private val chans = (1..5_000).map { Channel(it, it, "Channel $it", null, if (it % 2 == 0) "a" else "b", null, if (it % 7 == 0) 3 else 0) }
    private val live = object : LiveData {
        override val channels = chans
        override fun byId(id: Int) = chans.getOrNull(id - 1)
        override fun inCategory(cat: String) = chans.filter { it.categoryId == cat }
        override fun lang(id: Int) = if (id % 3 == 0) "hi" else "en"
        override fun sortKey(ch: Channel) = ch.name.lowercase()
        override fun isHd(ch: Channel) = ch.id % 5 == 0
        override fun hasGuide(ch: Channel) = false
        override fun collapse(list: List<Channel>, preferred: Map<String, Int>) = list
    }

    private fun liveQuery(selection: LiveSelection = LiveSelection.All, sort: Int = 0, find: String = "", filters: Set<String> = emptySet()) =
        LiveQuery("all", selection, sort, filters, find, emptySet(), emptySet(), listOf("en", "hi"), false, emptyList(), emptyMap())

    private fun movies() = (1..6_000).map { Movie(it, "Movie $it", null, if (it % 2 == 0) "a" else "b", 5.0, "mp4", it.toLong(), null, null) }
    private val vod = movies().let { list ->
        object : TitleSource<Movie> {
            override val byAdded = list.sortedByDescending { it.added }
            override val byRating = list
            override val byName = list
            override fun inCategory(cat: String) = list.filter { it.categoryId == cat }
            override fun byId(id: Int) = list.getOrNull(id - 1)
            override fun lang(id: Int) = if (id % 3 == 0) "hi" else "en"
            override fun id(t: Movie) = t.id
            override fun category(t: Movie) = t.categoryId
        }
    }
    private fun titleQuery(sel: TitleSelection, blocked: Set<String> = emptySet(), hidden: Set<String> = emptySet()) =
        TitleQuery(sel, blocked, hidden, listOf("en", "hi"))

    @Test
    fun aCheckThatDoesNothingChangesNoLive() {
        for (q in listOf(
            liveQuery(), liveQuery(sort = 1), liveQuery(sort = 2), liveQuery(find = "channel 12"),
            liveQuery(LiveSelection.Language("hi")), liveQuery(filters = setOf(LiveFilters.HD, LiveFilters.CATCHUP)),
        )) {
            var calls = 0
            val plain = buildLiveList(live, q).channels.map { it.id }
            val checked = buildLiveList(live, q) { calls++ }.channels.map { it.id }
            assertEquals(plain, checked)
            assertTrue("asked at least once for $q", calls > 0)
        }
    }

    @Test
    fun aLiveBuildStopsWhenTheCheckThrows() {
        for (q in listOf(liveQuery(), liveQuery(sort = 1), liveQuery(find = "channel"), liveQuery(LiveSelection.Language("hi")))) {
            try {
                buildLiveList(live, q) { throw Stop() }
                fail("should have stopped: $q")
            } catch (_: Stop) {
            }
        }
    }

    @Test
    fun aLiveBuildKeepsAskingAsTheListIsWalked() {
        var calls = 0
        buildLiveList(live, liveQuery()) { calls++ }
        // 5,000 channels: at least two asks while filtering and two while ranking, plus one per stage.
        assertTrue("asked $calls times", calls >= 6)
    }

    @Test
    fun aStoppedBuildLeavesThePreviousResultAlone() {
        var shown = buildLiveList(live, liveQuery())
        val before = shown
        try {
            shown = buildLiveList(live, liveQuery(find = "9")) { throw Stop() }
        } catch (_: Stop) {
        }
        assertEquals(before, shown)
    }

    @Test
    fun aCheckThatDoesNothingChangesNoTitleList() {
        for (q in listOf(
            titleQuery(TitleSelection.Recent), titleQuery(TitleSelection.All, hidden = setOf("a")),
            titleQuery(TitleSelection.Language("hi")), titleQuery(TitleSelection.Top, blocked = setOf("b")),
        )) {
            var calls = 0
            val plain = buildTitleList(vod, q).map { it.id }
            val checked = buildTitleList(vod, q) { calls++ }.map { it.id }
            assertEquals(plain, checked)
            assertTrue("asked at least once for $q", calls > 0)
        }
    }

    @Test
    fun aTitleBuildStopsWhenTheCheckThrows() {
        try {
            buildTitleList(vod, titleQuery(TitleSelection.Recent, hidden = setOf("a"))) { throw Stop() }
            fail("should have stopped")
        } catch (_: Stop) {
        }
    }
}
