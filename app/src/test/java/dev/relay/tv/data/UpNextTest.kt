package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {
    private fun ep(id: String, s: Int, n: Int) = Episode(id, s, n, "Ep $n", "mkv", null, null, null)
    private val show = listOf(ep("a", 1, 1), ep("b", 1, 2), ep("c", 2, 1), ep("x", 0, 1))
    private fun h(key: String, series: Int, pos: Long, updated: Long) =
        WatchEntry(key, "episode", "Show", streamId = key.removePrefix("ep:"), ext = "mkv", seriesId = series, position = pos, duration = 1000_000, updated = updated)

    @Test
    fun theEpisodeAfterAFinishedOneIsUpNext() {
        val r = UpNext.build(listOf(h("ep:a", 7, 990_000, 100)), { show }, emptyMap())
        assertEquals(listOf("b"), r.map { it.episode.id })
    }

    @Test
    fun theNextSeasonFollowsTheLastEpisodeAndSpecialsComeLast() {
        val r = UpNext.build(listOf(h("ep:b", 7, 990_000, 100)), { show }, emptyMap())
        assertEquals("c", r.single().episode.id)
        assertTrue(UpNext.build(listOf(h("ep:c", 7, 990_000, 100)), { show }, emptyMap()).single().episode.id == "x")
    }

    @Test
    fun anEpisodeInProgressIsContinueWatchingNotUpNext() {
        val r = UpNext.build(listOf(h("ep:a", 7, 990_000, 100), h("ep:b", 7, 200_000, 200)), { show }, emptyMap())
        assertTrue(r.isEmpty())
    }

    @Test
    fun alreadyWatchedEpisodesAreSkipped() {
        val r = UpNext.build(listOf(h("ep:a", 7, 990_000, 200), h("ep:b", 7, 999_000, 50)), { show }, emptyMap())
        assertEquals("c", r.single().episode.id)
    }

    @Test
    fun aDismissedSeriesReturnsOnceItIsPlayedAgain() {
        val hist = listOf(h("ep:a", 7, 990_000, 100))
        assertTrue(UpNext.build(hist, { show }, mapOf(Dismissals.upNext(7) to 150L)).isEmpty())
        val later = listOf(h("ep:a", 7, 990_000, 300))
        assertEquals(1, UpNext.build(later, { show }, mapOf(Dismissals.upNext(7) to 150L)).size)
    }

    @Test
    fun unknownEpisodesAndHiddenSeriesGiveNothing() {
        val hist = listOf(h("ep:a", 7, 990_000, 100))
        assertTrue(UpNext.build(hist, { null }, emptyMap()).isEmpty())
        assertTrue(UpNext.build(hist, { show }, emptyMap(), allowed = { false }).isEmpty())
        assertTrue(UpNext.build(listOf(h("ep:c", 7, 990_000, 1), h("ep:x", 7, 990_000, 2)), { show }, emptyMap()).isEmpty())
    }

    @Test
    fun seriesToLoadAreTheRecentFinishedOnes() {
        val hist = listOf(h("ep:a", 1, 990_000, 300), h("ep:a", 2, 10_000, 400), h("ep:b", 3, 990_000, 100))
        assertEquals(listOf(1, 3), UpNext.seriesToLoad(hist))
        val dismissed = mapOf(Dismissals.upNext(1) to 350L)
        assertEquals(listOf(3), UpNext.seriesToLoad(hist, max = 1, dismissed = dismissed))
        assertEquals(listOf(1), UpNext.seriesToLoad(hist, max = 2, allowed = { it != 3 }))
    }
}
