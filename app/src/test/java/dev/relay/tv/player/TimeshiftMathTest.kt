package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeshiftMathTest {
    private val rate = 1_000_000.0 // bytes per second

    @Test
    fun playingByteIsTheOffsetPlusWhatHasPlayed() {
        assertEquals(5_000_000L + 12_500_000L, TimeshiftMath.playingByte(5_000_000L, 12_500, rate))
        assertEquals(0L, TimeshiftMath.playingByte(0L, 0, rate))
    }

    @Test
    fun behindIsTheRecordedBytesNotYetPlayedInSeconds() {
        assertEquals(30, TimeshiftMath.behindSec(100_000_000L, 70_000_000L, rate))
    }

    @Test
    fun behindIsNeverNegativeAndZeroWhenTheBitrateIsUnknown() {
        assertEquals(0, TimeshiftMath.behindSec(1_000L, 5_000L, rate))
        assertEquals(0, TimeshiftMath.behindSec(100_000_000L, 0L, 0.0))
    }

    @Test
    fun seekingBackMovesEarlierByTheSecondsAsked() {
        val t = TimeshiftMath.seekTarget(70_000_000L, -10, rate, 100_000_000L, 0L)
        assertEquals(60_000_000L - 60_000_000L % 188, t)
    }

    @Test
    fun seekingForwardStopsTwoSecondsShortOfTheLiveEdge() {
        val t = TimeshiftMath.seekTarget(70_000_000L, 600, rate, 100_000_000L, 0L)
        assertEquals(98_000_000L - 98_000_000L % 188, t)
    }

    @Test
    fun seekingBackStopsAtTheOldestByteStillKept() {
        val t = TimeshiftMath.seekTarget(10_000_000L, -600, rate, 100_000_000L, 4_000_000L)
        assertEquals(4_000_000L - 4_000_000L % 188, t)
    }

    @Test
    fun seekTargetsLandOnTsPacketBoundaries() {
        val t = TimeshiftMath.seekTarget(70_000_017L, -7, rate, 100_000_000L, 0L)
        assertEquals(0L, t % TimeshiftMath.TS_PACKET)
    }

    @Test
    fun goingLiveUsesAHugeForwardSkip() {
        val live = TimeshiftMath.seekTarget(70_000_000L, Int.MAX_VALUE / 4, rate, 100_000_000L, 0L)
        assertEquals(98_000_000L - 98_000_000L % 188, live)
    }

    @Test
    fun nothingShowsWhilePlayingWithinThreeSecondsOfLive() {
        assertNull(TimeshiftMath.barText(0, paused = false, tv = true))
        assertNull(TimeshiftMath.barText(3, paused = false, tv = false))
    }

    @Test
    fun barShowsTheDelayWhenPlayingBehind() {
        assertEquals("1:05 behind live   ◀ −10s   ▶ +30s", TimeshiftMath.barText(65, paused = false, tv = true))
        assertEquals("1:05 behind live", TimeshiftMath.barText(65, paused = false, tv = false))
    }

    @Test
    fun pausedAlwaysShowsAndTvAddsThePlayHint() {
        assertEquals("⏸  Paused · 0:00 behind live   ◀ −10s   ▶ +30s   OK play", TimeshiftMath.barText(0, paused = true, tv = true))
        assertEquals("⏸  Paused · 0:02 behind live", TimeshiftMath.barText(2, paused = true, tv = false))
    }

    @Test
    fun anHourOrMoreBehindShowsHours() {
        assertEquals("1:01:01 behind live", TimeshiftMath.barText(3661, paused = false, tv = false))
    }
}
