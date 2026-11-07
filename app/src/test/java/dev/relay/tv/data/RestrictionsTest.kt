package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionsTest {

    private fun ch(id: Int, num: Int, cat: String) = Channel(id, num, "Ch $id", null, cat, null, 0)

    private val kidsChannel = ch(1, 7, "kids")
    private val newsChannel = ch(2, 8, "news")
    private val adultChannel = ch(3, 5, "xxx")
    private val channels = listOf(kidsChannel, newsChannel, adultChannel)
    private val adult = setOf("xxx", "xxx-vod", "xxx-series")

    private fun restrictions(on: Boolean) = Restrictions(
        on, adult,
        channelCat = { id -> channels.firstOrNull { it.id == id }?.categoryId },
        movieCat = { id -> mapOf(10 to "films", 11 to "xxx-vod")[id] },
        seriesCat = { id -> mapOf(20 to "cartoons", 21 to "xxx-series")[id] },
    )

    private fun live(c: Channel) = PlayItem("live:${c.id}", "live", c.name, streamId = c.id.toString(), ext = "ts")

    @Test
    fun numberLookupOnKidsProfileCannotReachAdultChannel() {
        val r = restrictions(true)
        assertNull(r.findByNumber(channels, 5))
        assertEquals(kidsChannel, r.findByNumber(channels, 7))
    }

    @Test
    fun numberLookupWithoutRestrictionFindsEverything() {
        assertEquals(adultChannel, restrictions(false).findByNumber(channels, 5))
    }

    @Test
    fun liveListDropsAdultChannelsAndKeepsTheChosenOne() {
        val req = PlayRequest("p", channels.map(::live), index = 1, live = true)
        val ok = restrictions(true).admit(req)
        assertNotNull(ok)
        assertEquals(listOf("live:1", "live:2"), ok!!.items.map { it.key })
        assertEquals("live:2", ok.items[ok.index].key)
    }

    @Test
    fun fallbackVersionsInBlockedCategoriesAreDropped() {
        val item = live(newsChannel).copy(alternates = listOf("3", "1", "999"))
        val ok = restrictions(true).admit(PlayRequest("p", listOf(item), 0, live = true))!!
        assertEquals(listOf("1"), ok.items[0].alternates)
        assertEquals(listOf("3", "1", "999"), restrictions(false).admit(PlayRequest("p", listOf(item), 0, live = true))!!.items[0].alternates)
    }

    @Test
    fun choosingAnAdultChannelIsBlocked() {
        val req = PlayRequest("p", channels.map(::live), index = 2, live = true)
        assertNull(restrictions(true).admit(req))
    }

    @Test
    fun everyKindOfPlaybackIsChecked() {
        val r = restrictions(true)
        fun item(kind: String, id: String, series: Int? = null) = PlayItem("k", kind, "t", streamId = id, ext = "ts", seriesId = series)
        assertTrue(r.allows(item("movie", "10")))
        assertFalse(r.allows(item("movie", "11")))
        assertTrue(r.allows(item("episode", "999", series = 20)))
        assertFalse(r.allows(item("episode", "999", series = 21)))
        assertTrue(r.allows(item("catchup", "2")))
        assertFalse(r.allows(item("catchup", "3")))
        // History entries for titles no longer in the catalog can't be checked, so they're blocked.
        assertFalse(r.allows(item("movie", "404")))
        assertFalse(r.allows(item("episode", "1", series = null)))
    }

    @Test
    fun unrestrictedProfilesPassUnchanged() {
        val req = PlayRequest("p", channels.map(::live), index = 2, live = true)
        assertEquals(req, restrictions(false).admit(req))
        assertEquals(req, Restrictions.NONE.admit(req))
    }
}
