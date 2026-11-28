package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun defaultsPutYourRowsFirstThenFeaturedLanguages() {
        val rows = HomeLayout.defaults(listOf("en", "hi", "bn"))
        assertEquals(
            listOf("continue", "upNext", "recommended", "because1", "because2", "favChannels", "recentChannels", "lang:en", "lang:hi", "lang:bn"),
            rows.take(10),
        )
        assertEquals(HomeLayout.CONTINUE, HomeLayout.liveFirst(emptyList())[2])
    }

    @Test
    fun savedRowsWinAndUnknownLanguagesAreDropped() {
        assertEquals(listOf("myList", "lang:bn"), HomeLayout.resolve(listOf("myList", "lang:bn", "lang:xx"), listOf("en")))
        assertEquals(HomeLayout.defaults(listOf("en")), HomeLayout.resolve(null, listOf("en")))
    }

    @Test
    fun hiddenRowsStillAppearInTheEditor() {
        val all = HomeLayout.all(listOf("myList"), listOf("en"))
        assertEquals("myList", all.first())
        assertTrue("continue" in all && "lang:bd" in all && "lang:south" in all)
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun moveAndToggle() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), HomeLayout.move(rows, "b", -1))
        assertEquals(rows, HomeLayout.move(rows, "a", -1))
        assertEquals(rows, HomeLayout.move(rows, "c", 1))
        assertEquals(listOf("a", "c"), HomeLayout.toggle(rows, "b"))
        assertEquals(listOf("a", "b", "c", "d"), HomeLayout.toggle(rows, "d"))
        assertFalse("x" in rows.moved("x", 1))
    }

    @Test
    fun bangladeshSitsUnderBengaliInTheLanguageView() {
        assertEquals(listOf("en", "hi", "bn", "bd", "south"), Langs.viewOrder(listOf("en", "hi", "bn", "south", "bd")))
        assertEquals(listOf("bn", "bd", "en", "hi", "south"), Langs.viewOrder(listOf("bd", "bn", "en")))
        assertEquals(listOf("en", "hi", "bn", "bd", "south"), Langs.viewOrder(emptyList()))
    }

    @Test
    fun rankingKeepsOthersLowerButPresent() {
        val r = LangRank(listOf("bn", "en"))
        assertTrue(r.rank("bn") < r.rank("en"))
        assertTrue(r.rank("en") < r.rank(null))
        assertTrue(r.rank(null) < r.rank("other"))
        assertTrue(r.rank("hi") == r.rank("other"))
    }
}
