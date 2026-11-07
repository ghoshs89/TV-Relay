package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class LibraryEditsTest {

    @Test
    fun aFullChangeParses() {
        val c = LibraryEdits.parse(
            """{"hidden":["livegroup:AR","vod:12"],"pinnedLive":["4","4","9"],"pinnedVod":["3"],"pinnedSeries":[],
               "favLive":[7,"8"],"renames":{"7":"  My News  ","9":""}}""",
        )
        assertEquals(setOf("livegroup:AR", "vod:12"), c.hidden)
        assertEquals(listOf("4", "9"), c.pinnedLive)
        assertEquals(listOf(7, 8), c.favLive)
        assertEquals(mapOf(7 to "My News", 9 to ""), c.renames)
    }

    @Test
    fun missingPartsLeaveFavouritesAndNamesAlone() {
        val c = LibraryEdits.parse("""{"hidden":[]}""")
        assertNull(c.favLive)
        assertNull(c.renames)
    }

    @Test
    fun badRequestsAreRejected() {
        reject("not json")
        reject("""{"hidden":"vod:1"}""")
        reject("""{"hidden":["drop table"]}""")
        reject("""{"hidden":["other:1"]}""")
        reject("""{"favLive":["x"]}""")
        reject("""{"renames":{"abc":"x"}}""")
        reject("""{"renames":[1]}""")
    }

    @Test
    fun favouritesCanBeReorderedAndRemovedButNotAdded() {
        val cur = listOf(FavItem("live", 1, "A"), FavItem("movie", 5, "M"), FavItem("live", 2, "B"), FavItem("live", 3, "C"))
        val out = LibraryEdits.reorderFavs(cur, listOf(3, 1, 99, 3))
        assertEquals(listOf(3, 1, 5), out.map { it.id })
        assertEquals("movie", out.last().kind)
    }

    @Test
    fun emptyNameRestoresTheProviderName() {
        assertEquals(mapOf(1 to "X", 3 to "Z"), LibraryEdits.mergeRenames(mapOf(1 to "A", 2 to "B"), mapOf(1 to "X", 2 to "", 3 to "Z")))
    }

    private fun reject(text: String) {
        try {
            LibraryEdits.parse(text)
            fail("Accepted: $text")
        } catch (_: IllegalArgumentException) {
        }
    }
}
