package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipSizesTest {
    @Test
    fun choosingCyclesThroughTheOptionsAndWraps() {
        var v = 5
        val seen = mutableListOf(v)
        repeat(SkipSizes.OPTIONS.size) { v = SkipSizes.next(v); seen += v }
        assertEquals(listOf(5, 10, 15, 30, 60, 5), seen)
    }

    @Test
    fun anUnknownSizeStartsAgainFromTheFirst() {
        assertEquals(5, SkipSizes.next(7))
        assertEquals(5, SkipSizes.next(0))
    }

    @Test
    fun theDefaultsAreTheOldFixedJumps() {
        assertEquals(10, SkipSizes.DEFAULT_BACK)
        assertEquals(30, SkipSizes.DEFAULT_FORWARD)
        assertEquals(true, SkipSizes.DEFAULT_BACK in SkipSizes.OPTIONS && SkipSizes.DEFAULT_FORWARD in SkipSizes.OPTIONS)
    }

    @Test
    fun aSavedSizeThatIsNotOfferedFallsBack() {
        assertEquals(15, SkipSizes.clean(15, 10))
        assertEquals(10, SkipSizes.clean(0, 10))
        assertEquals(30, SkipSizes.clean(-30, 30))
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals("30 seconds", SkipSizes.label(30))
    }
}
