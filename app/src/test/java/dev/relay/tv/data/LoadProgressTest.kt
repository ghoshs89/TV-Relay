package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadProgressTest {

    private fun dl(big: Boolean, bytes: Long, expected: Long?, finished: Boolean = false) =
        LoadSteps.Download("x", big).also { it.bytes = bytes; it.expected = expected; it.finished = finished }

    @Test
    fun bytesAgainstExpectedSizes() {
        val f = LoadSteps.downloadFraction(listOf(dl(true, 25, 100), dl(true, 50, 100), dl(false, 1, null, finished = true)))
        assertEquals(76f / 201f, f!!, 0.001f)
    }

    @Test
    fun unknownSizeOfABigListMeansNoPercent() {
        assertNull(LoadSteps.downloadFraction(listOf(dl(true, 25, null), dl(true, 50, 100))))
        // Once that list has finished, its size is known.
        assertEquals(1f, LoadSteps.downloadFraction(listOf(dl(true, 25, null, true), dl(true, 100, 100, true))))
    }

    @Test
    fun aListLargerThanLastTimeDoesNotPassFullBeforeItEnds() {
        val f = LoadSteps.downloadFraction(listOf(dl(true, 150, 100), dl(true, 100, 100, true)))!!
        assertEquals(0.99f, f, 0.0001f)
    }

    @Test
    fun spansAndText() {
        assertEquals(40, LoadSteps.span(0, 80, 0.5f))
        assertEquals(80, LoadSteps.span(0, 80, 3f))
        assertEquals(90, LoadSteps.span(90, 100, -1f))
        assertEquals("42% · Downloading movies", LoadProgress("Downloading movies", 42).text)
        assertEquals("Downloading · 2.0 MB", LoadProgress("Downloading · ${LoadSteps.megabytes(2_097_152)}", null).text)
    }
}
