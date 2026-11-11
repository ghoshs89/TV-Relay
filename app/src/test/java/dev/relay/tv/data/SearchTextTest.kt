package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    private val regex = Regex("[^\\p{L}\\p{N}]+")
    private fun old(s: String) = s.lowercase().replace(regex, " ").trim()

    @Test
    fun normMatchesTheRegexItReplaces() {
        val samples = listOf(
            "", "  ", "Star Plus HD", "CA: CBC  CALGARY", "★ News ★", "Amélie (2001)", "Ñandú & Co.", "Dil Chahta Hai - 2001",
            "অনুরাগের ছোঁয়া", "देवदास", "名探偵コナン", "Movie²", "Ⅻ Angry Men", "A_B-C.D", "😀 Party 😀", "x", "  lead and trail  ",
            "İstanbul", "ǅ title",
        )
        for (s in samples) assertEquals("for \"$s\"", old(s), SearchText.norm(s))
    }

    @Test
    fun matchesEveryWordInAnyOrder() {
        val m = SearchText.matcher("dil hai")!!
        assertTrue(m("Dil Chahta Hai (2001)"))
        assertTrue(m("HAI dil"))
        assertFalse(m("Dil Chahta"))
    }

    @Test
    fun allowsOneTypoInASingleLongWord() {
        val m = SearchText.matcher("terminater")!!
        assertTrue(m("The Terminator (1984)"))
        assertFalse(SearchText.matcher("termin")!!.invoke("The Tarminator"))
        // Short single words and multi-word queries don't get the typo allowance.
        assertFalse(SearchText.matcher("star")!!.invoke("Stor Wars"))
        assertFalse(SearchText.matcher("dil hay")!!.invoke("Dil Hai"))
    }

    @Test
    fun emptyOrSingleCharacterQueriesDoNotSearch() {
        assertNull(SearchText.matcher(""))
        assertNull(SearchText.matcher("  - "))
        assertNull(SearchText.matcher("a"))
        assertNotNull(SearchText.matcher("ab"))
        assertNotNull(SearchText.matcher("a b"))
    }

    @Test
    fun oneEditCases() {
        assertTrue(SearchText.oneEdit("kitten", "kitten"))
        assertTrue(SearchText.oneEdit("kitten", "sitten"))
        assertTrue(SearchText.oneEdit("kitten", "kittn"))
        assertTrue(SearchText.oneEdit("kitten", "kittens"))
        assertFalse(SearchText.oneEdit("kitten", "sittin"))
        assertFalse(SearchText.oneEdit("kitten", "kit"))
    }
}
