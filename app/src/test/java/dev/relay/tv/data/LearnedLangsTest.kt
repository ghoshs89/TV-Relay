package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedLangsTest {
    private val guesses = mapOf((true to 1) to "hi", (false to 1) to "en")
    private val guess: (Boolean, Int) -> String? = { movie, id -> guesses[movie to id] }

    @Test
    fun learningWhatWasAlreadyGuessedChangesNothingOnScreen() {
        val l = LearnedLangs()
        assertEquals(LearnedLangs.Change.Confirmed, l.learn("m:1", "hi", guess))
        assertEquals("hi", l.movie(1))
    }

    @Test
    fun learningADifferentGroupMovesTheTitle() {
        val l = LearnedLangs()
        assertEquals(LearnedLangs.Change.Moved, l.learn("m:1", "bn", guess))
        assertEquals(LearnedLangs.Change.Moved, l.learn("m:2", "en", guess)) // no guess at all
    }

    @Test
    fun learningTheSameAgainIsNothing() {
        val l = LearnedLangs()
        l.learn("m:1", "bn", guess)
        assertEquals(LearnedLangs.Change.None, l.learn("m:1", "bn", guess))
    }

    @Test
    fun aCorrectionBackToTheGuessStillMoves() {
        val l = LearnedLangs()
        l.learn("m:1", "bn", guess)
        assertEquals(LearnedLangs.Change.Moved, l.learn("m:1", "hi", guess))
    }

    @Test
    fun moviesAndSeriesWithTheSameNumberStayApart() {
        val l = LearnedLangs()
        l.learn("m:1", "bn", guess)
        assertNull(l.series(1))
        assertEquals(LearnedLangs.Change.Confirmed, l.learn("s:1", "en", guess))
        assertEquals("bn", l.movie(1))
        assertEquals("en", l.series(1))
    }

    @Test
    fun savedFormRoundTripsAndIgnoresRubbish() {
        val l = LearnedLangs()
        l.load(mapOf("m:5" to "hi", "s:9" to "en", "x:1" to "en", "m:abc" to "en", "" to "en"))
        assertEquals(2, l.size)
        assertEquals(mapOf("m:5" to "hi", "s:9" to "en"), l.snapshot(100))
        assertEquals(LearnedLangs.Change.None, l.learn("bad", "en", guess))
    }

    @Test
    fun snapshotIsCapped() {
        val l = LearnedLangs()
        for (i in 1..20) l.learn("m:$i", "en", guess)
        assertEquals(7, l.snapshot(7).size)
    }
}
