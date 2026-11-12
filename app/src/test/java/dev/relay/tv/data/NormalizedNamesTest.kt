package dev.relay.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class NormalizedNamesTest {
    private val corpus = listOf(
        "UK: Sky News HD", "IN - Zee TV (FHD)", "Kahaani 2 (2016)", "স্টার জলসা", "", "  ", "Spider-Man: No Way Home", "ÉCLAIR 4K",
    )

    @Test
    fun preparedNamesGiveTheSameAnswersAsMatchingRawNames() {
        val names = NormalizedNames<String> { it }.of(corpus)
        for (q in listOf("sky", "zee tv", "kahani", "kahaani", "spider man", "no way", "eclair", "জলসা", "2016", "zz", "news hd")) {
            val raw = SearchText.matcher(q)!!
            val ready = SearchText.matcherNormalized(q)!!
            corpus.forEachIndexed { i, n -> assertEquals("$q on $n", raw(n), ready(names[i])) }
        }
    }

    @Test
    fun tooShortAQueryMatchesNothingEitherWay() {
        assertEquals(null, SearchText.matcherNormalized("a"))
        assertEquals(null, SearchText.matcherNormalized("  "))
    }

    @Test
    fun theSameListIsPreparedOnce() {
        var calls = 0
        val n = NormalizedNames<String> { calls++; it }
        val first = n.of(corpus)
        assertSame(first, n.of(corpus))
        assertEquals(corpus.size, calls)
        assertArrayEquals(corpus.map { SearchText.norm(it) }.toTypedArray(), first)
    }

    @Test
    fun aNewListOrAClearPreparesAgain() {
        val n = NormalizedNames<String> { it }
        val first = n.of(corpus)
        val other = corpus.toList()
        assertNotSame(first, n.of(other))
        n.clear()
        assertEquals(other.size, n.of(other).size)
    }
}
