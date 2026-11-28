package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    private fun m(id: Int, name: String, tmdb: String? = null, cat: String = "1", kind: String? = "action", lang: String? = "en", rating: Double = 7.0, year: String? = "2020") =
        TitleRef(false, id, name, year, tmdb, cat, rating, 0, lang, kind)

    private val lib = listOf(
        m(1, "Dune", "438631"), m(2, "Arrival", "329865"), m(3, "Arrival 4K", "329865"),
        m(4, "Blade Runner 2049", "335984"), m(5, "Interstellar", "157336"), m(6, "Paddington", "116149", cat = "9", kind = "kids"),
        m(7, "Sicario", "273481"), m(8, "Prisoners", "146233"),
    )
    private val byId = lib.associateBy { it.id }
    private val lookup: (Boolean, Int) -> TitleRef? = { tv, id -> if (tv) null else byId[id] }
    private fun watched(id: Int, at: Long) = WatchEntry("movie:$id", "movie", "x", streamId = "$id", ext = "mkv", position = 990, duration = 1000, updated = at)
    private val langs = LangRank(listOf("en"))
    private fun match(r: Tmdb.Rec) = lib.filter { it.tmdbId == r.id }
    private fun rec(id: String, title: String) = Tmdb.Rec(id, false, title)

    @Test
    fun seedsAreFinishedFilmsMostRecentFirst() {
        val hist = listOf(watched(1, 100), watched(5, 300), WatchEntry("movie:4", "movie", "x", streamId = "4", ext = "mkv", position = 10, duration = 1000, updated = 500))
        assertEquals(listOf(5, 1), Recommendations.seeds(hist, emptyList(), lookup).map { it.title.id })
    }

    @Test
    fun tmdbRecommendationsAreMatchedToTheLibraryOnceAndWatchedOnesLeftOut() {
        val hist = listOf(watched(1, 100), watched(5, 50))
        val seeds = Recommendations.seeds(hist, emptyList(), lookup)
        val recs = mapOf(
            TitleVersions.groupKey(byId[1]!!) to listOf(rec("329865", "Arrival"), rec("335984", "Blade Runner 2049"), rec("157336", "Interstellar"), rec("999", "Not here")),
            TitleVersions.groupKey(byId[5]!!) to listOf(rec("329865", "Arrival")),
        )
        val filter = Recommendations.Filter(Recommendations.seen(hist, lookup), emptyMap()) { true }
        val rows = Recommendations.fromTmdb(seeds, recs, ::match, filter, langs)
        // Arrival is recommended by both seeds, so it comes first, once (two copies); Interstellar was watched.
        assertEquals(listOf("Arrival", "Blade Runner 2049"), rows.forYou.map { Tmdb.cleanTitle(it.title.name) })
        assertEquals("Because you watched Dune", rows.forYou.first().reason)
    }

    @Test
    fun notInterestedAndRestrictedTitlesAreNeverOffered() {
        val hist = listOf(watched(1, 100))
        val seeds = Recommendations.seeds(hist, emptyList(), lookup)
        val recs = mapOf(TitleVersions.groupKey(byId[1]!!) to listOf(rec("329865", "Arrival"), rec("335984", "Blade Runner 2049"), rec("116149", "Paddington")))
        val dismissed = mapOf(Dismissals.notInterested(TitleVersions.groupKey(byId[4]!!)) to 1L)
        val filter = Recommendations.Filter(Recommendations.seen(hist, lookup), dismissed) { it.kind != "kids" }
        val rows = Recommendations.fromTmdb(seeds, recs, ::match, filter, langs)
        assertEquals(listOf(2), rows.forYou.map { it.title.id }.filter { it == 2 || it == 3 }.take(1).map { 2 })
        assertFalse(rows.forYou.any { it.title.id == 4 || it.title.id == 6 })
    }

    @Test
    fun withoutAKeyTheLibraryIsRankedByLikeness() {
        val hist = listOf(watched(1, 100))
        val seeds = Recommendations.seeds(hist, emptyList(), lookup)
        val filter = Recommendations.Filter(Recommendations.seen(hist, lookup), emptyMap()) { true }
        val rows = Recommendations.local(seeds, lib, filter, langs, 0)
        assertTrue(rows.forYou.isNotEmpty())
        assertFalse(rows.forYou.any { it.title.id == 1 })
        assertFalse(rows.forYou.any { it.title.id == 6 }) // a kids film in another category is not like Dune
        assertEquals(1, rows.forYou.count { it.title.tmdbId == "329865" })
        assertTrue(rows.forYou.all { it.reason == "Because you watched Dune" })
        assertEquals(1, rows.because.size)
    }

    @Test
    fun aNewProfileGetsTopPicksInItsLanguage() {
        val filter = Recommendations.Filter(emptySet(), emptyMap()) { true }
        val rows = Recommendations.local(emptyList(), lib, filter, langs, 0)
        assertEquals("Top pick in English", rows.forYou.first().reason)
        assertTrue(rows.because.isEmpty())
    }

    @Test
    fun moreLikeThisLeavesOutTheTitleItself() {
        val filter = Recommendations.Filter(emptySet(), emptyMap()) { true }
        val more = Recommendations.moreLikeThis(byId[2]!!, null, ::match, lib, filter, langs)
        assertFalse(more.any { it.tmdbId == "329865" })
        assertTrue(more.isNotEmpty())
    }

    @Test
    fun disallowedSeedsAreExcludedAndMyListSeedsSaySaved() {
        val hist = listOf(watched(6, 500))
        val favs = listOf(FavItem("movie", 1, "Dune", at = 100L))
        val seeds = Recommendations.seeds(hist, favs, lookup, allowed = { it.kind != "kids" })
        assertEquals(listOf(1), seeds.map { it.title.id })
        assertEquals("Because you saved Dune", seeds.single().becauseLabel)
    }
}
