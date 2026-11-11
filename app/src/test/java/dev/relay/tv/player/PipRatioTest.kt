package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PipRatioTest {
    @Test
    fun anUnknownSizeIsSixteenByNine() {
        assertEquals(16 to 9, PipRatio.safe(0, 0))
        assertEquals(16 to 9, PipRatio.safe(1920, 0))
    }

    @Test
    fun normalVideoKeepsItsOwnShape() {
        assertEquals(1920 to 1080, PipRatio.safe(1920, 1080))
        assertEquals(1080 to 1920, PipRatio.safe(1080, 1920))
    }

    @Test
    fun veryWideIsClampedToTheWidestAndroidAccepts() {
        assertEquals(239 to 100, PipRatio.safe(3000, 1000))
    }

    @Test
    fun veryTallIsClampedToTheTallestAndroidAccepts() {
        assertEquals(100 to 239, PipRatio.safe(400, 1200))
    }

    @Test
    fun theLimitsThemselvesAreAccepted() {
        assertEquals(239 to 100, PipRatio.safe(239, 100))
        assertEquals(42 to 100, PipRatio.safe(42, 100))
    }
}
