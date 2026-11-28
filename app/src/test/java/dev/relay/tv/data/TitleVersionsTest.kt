package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TitleVersionsTest {
    private fun m(id: Int, name: String, year: String? = null, tmdb: String? = null, lang: String? = null, rating: Double = 6.0) =
        TitleRef(false, id, name, year, tmdb, "1", rating, 0, lang)

    @Test
    fun copiesWithTheSameTmdbIdAreOneTitle() {
        assertEquals(TitleVersions.groupKey(m(1, "Dune 4K", tmdb = "438631")), TitleVersions.groupKey(m(2, "HI - Dune", tmdb = "438631")))
    }

    @Test
    fun copiesWithoutTmdbIdMatchOnCleanedNameAndYear() {
        val a = TitleVersions.groupKey(m(1, "Dune (2021) [4K]"))
        val b = TitleVersions.groupKey(m(2, "EN - Dune (2021) FHD"))
        assertEquals(a, b)
        assertNotEquals(a, TitleVersions.groupKey(m(3, "Dune (1984)")))
    }

    @Test
    fun aFilmAndASeriesOfTheSameNameStaySeparate() {
        assertNotEquals(TitleVersions.groupKey(false, "Fargo", "1996", null), TitleVersions.groupKey(true, "Fargo", "1996", null))
    }

    @Test
    fun collapseKeepsTheViewersLanguageThenTheBestPicture() {
        val langs = LangRank(listOf("hi", "en"))
        val picked = TitleVersions.collapse(
            listOf(m(1, "Dune 4K", tmdb = "1", lang = "en"), m(2, "Dune HD", tmdb = "1", lang = "hi"), m(3, "Dune FHD", tmdb = "1", lang = "hi")),
            langs,
        )
        assertEquals(listOf(3), picked.map { it.id })
    }

    @Test
    fun distinctTitlesAreKeptInOrder() {
        val picked = TitleVersions.collapse(listOf(m(1, "Alien (1979)"), m(2, "Aliens (1986)"), m(3, "Alien (1979) HD")), LangRank(emptyList()))
        // The two copies of Alien are one title (the HD copy is kept); Aliens stays separate.
        assertEquals(listOf(3, 2), picked.map { it.id })
    }
}
