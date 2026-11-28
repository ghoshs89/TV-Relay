package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpNextRulesTest {
    private val hour = 3_600_000L

    @Test
    fun theNextEpisodeCountsDownInTheLastThirtySeconds() {
        assertEquals(UpNextRules.NextEpisode(12), UpNextRules.offer("episode", hour, hour - 11_500, hasNext = true, autoNext = true, hidden = false))
        assertNull(UpNextRules.offer("episode", hour, hour - 31_000, hasNext = true, autoNext = true, hidden = false))
    }

    @Test
    fun withoutAutoNextThereIsNoCountdown() {
        assertNull(UpNextRules.offer("episode", hour, hour - 5_000, hasNext = true, autoNext = false, hidden = false))
    }

    @Test
    fun aFilmOrFinaleOffersARecommendation() {
        assertEquals(UpNextRules.Recommend, UpNextRules.offer("movie", hour, hour - 5_000, hasNext = false, autoNext = true, hidden = false))
        assertEquals(UpNextRules.Recommend, UpNextRules.offer("episode", hour, hour - 5_000, hasNext = false, autoNext = false, hidden = false))
    }

    @Test
    fun liveShortClipsAndHiddenCardsShowNothing() {
        assertNull(UpNextRules.offer("live", hour, hour - 5_000, hasNext = false, autoNext = true, hidden = false))
        assertNull(UpNextRules.offer("catchup", hour, hour - 5_000, hasNext = false, autoNext = true, hidden = false))
        assertNull(UpNextRules.offer("movie", 4 * 60_000L, 4 * 60_000L - 5_000, hasNext = false, autoNext = true, hidden = false))
        assertNull(UpNextRules.offer("movie", hour, hour - 5_000, hasNext = false, autoNext = true, hidden = true))
    }
}
