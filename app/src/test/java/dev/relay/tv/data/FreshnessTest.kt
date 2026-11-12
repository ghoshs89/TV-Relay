package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessTest {
    private val delay = Freshness.START_DELAY_MS

    @Test
    fun theDelayIsTwentySeconds() {
        assertEquals(20_000L, delay)
    }

    @Test
    fun anOverdueLibraryJustInstalledWaitsTheWholeDelay() {
        assertEquals(delay, Freshness.startDelay(installedAt = 1_000, now = 1_000, due = true, force = false))
    }

    @Test
    fun theWaitCountsDownFromWhenItWasInstalled() {
        assertEquals(15_000L, Freshness.startDelay(1_000, 6_000, due = true, force = false))
        assertEquals(1L, Freshness.startDelay(1_000, 20_999, due = true, force = false))
        assertEquals(0L, Freshness.startDelay(1_000, 21_000, due = true, force = false))
        assertEquals(0L, Freshness.startDelay(1_000, 90_000, due = true, force = false))
    }

    @Test
    fun aRefreshTheUserAskedForNeverWaits() {
        assertEquals(0L, Freshness.startDelay(1_000, 1_000, due = true, force = true))
    }

    @Test
    fun nothingWaitsWhenNothingIsDue() {
        assertEquals(0L, Freshness.startDelay(1_000, 1_000, due = false, force = false))
    }

    @Test
    fun aProcessThatHasInstalledNothingDoesNotWait() {
        // First ever load, or a load that could not install anything: the refresh is the load.
        assertEquals(0L, Freshness.startDelay(-1, 5_000, due = true, force = false))
    }

    @Test
    fun aClockThatMovedBackwardsNeverWaitsLongerThanTheDelay() {
        assertEquals(delay, Freshness.startDelay(50_000, 1_000, due = true, force = false))
    }

    @Test
    fun libraryDueStillMeansSixHours() {
        val saved = 1_000_000L
        assertFalse(Freshness.libraryDue(saved, saved + 6 * 3600_000L - 1))
        assertTrue(Freshness.libraryDue(saved, saved + 6 * 3600_000L))
        assertTrue(Freshness.libraryDue(0L, saved))
    }

    @Test
    fun aFastDeviceWaitsLess() {
        org.junit.Assert.assertEquals(5_000L, Freshness.startDelay(1_000, 1_000, due = true, force = false, startDelayMs = 5_000))
        org.junit.Assert.assertEquals(0L, Freshness.startDelay(1_000, 7_000, due = true, force = false, startDelayMs = 5_000))
    }
}
