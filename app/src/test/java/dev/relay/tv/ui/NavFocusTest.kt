package dev.relay.tv.ui

import dev.relay.tv.data.Movie
import dev.relay.tv.data.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class NavFocusTest {

    private val movie = Movie(5, "Film", null, "c", 7.0, "mp4", 0, null, "2020")
    private val series = Series(9, "Show", null, "c", 8.0, null, "2021", null, 0, null)

    @Test
    fun backReturnsToHomesPositionEvenAfterTheDetailPageRemembersItsOwn() {
        val home = FocusMemory()
        val nav = Nav(Route.Home)
        home.lastKey = "ram:5"

        nav.push(Route.MovieDetail(movie))
        val detail = nav.memory(1, home)
        assertNotSame(home, detail)
        detail.lastKey = "md:play"
        detail.lastKey = "md:fav"

        nav.pop(home)
        assertEquals("ram:5", home.lastKey)
        assertEquals(1, home.restoreTick)
    }

    @Test
    fun eachLevelKeepsItsOwnPosition() {
        val home = FocusMemory()
        val nav = Nav(Route.Home)
        home.lastKey = "trs:9"
        nav.push(Route.SeriesDetail(series))
        nav.memory(1, home).lastKey = "ep:100"
        nav.push(Route.MovieDetail(movie))
        nav.memory(2, home).lastKey = "md:play"

        nav.pop(home)
        val seriesPage = nav.memory(1, home)
        assertEquals("ep:100", seriesPage.lastKey)
        assertEquals(1, seriesPage.restoreTick)
        assertEquals(0, home.restoreTick)

        nav.pop(home)
        assertEquals("trs:9", home.lastKey)
        assertEquals(1, home.restoreTick)
    }

    @Test
    fun homeAlwaysUsesTheSharedMemory() {
        val home = FocusMemory()
        val nav = Nav(Route.Profiles)
        nav.reset(Route.Home)
        assertEquals(home, nav.memory(0, home))
    }
}
