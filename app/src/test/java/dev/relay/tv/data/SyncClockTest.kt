package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {
    @Test
    fun timesAlwaysIncreaseEvenWithinOneMillisecond() {
        val c = SyncClock()
        val a = c.next(1_000)
        val b = c.next(1_000)
        val d = c.next(1_000)
        assertEquals(1_000L, a)
        assertTrue(b > a)
        assertTrue(d > b)
    }

    @Test
    fun aClockThatGoesBackwardsStillGivesLaterTimes() {
        val c = SyncClock()
        val a = c.next(5_000)
        val b = c.next(1_000)
        assertTrue(b > a)
    }

    @Test
    fun aTimeSeenFromAnotherScreenPushesLaterChangesPastIt() {
        val c = SyncClock()
        c.observe(9_000_000)
        assertTrue(c.next(1_000) > 9_000_000)
        assertEquals(9_000_001L, c.last)
    }

    @Test
    fun anOlderTimeSeenChangesNothing() {
        val c = SyncClock(500)
        c.observe(100)
        assertEquals(500L, c.last)
        assertEquals(1_000L, c.next(1_000))
    }

    @Test
    fun theLastTimeCanBeRestoredAfterARestart() {
        val c = SyncClock(7_000)
        assertEquals(7_001L, c.next(10))
    }
}
