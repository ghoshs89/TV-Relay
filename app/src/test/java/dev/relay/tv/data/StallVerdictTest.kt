package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StallVerdictTest {
    private val stall = 4_000L
    private val check = 1_000L

    private fun v(prev: Long, now: Long, beat: Long) = StallVerdict.verdict(prev, now, beat, stall, check)

    @Test fun normalBeatIsOk() {
        assertEquals(StallVerdict.Verdict.Ok, v(prev = 10_000, now = 11_000, beat = 10_900))
    }

    @Test fun lateByLessThanTheLimitIsOk() {
        assertEquals(StallVerdict.Verdict.Ok, v(prev = 10_000, now = 11_000, beat = 7_100))
    }

    @Test fun fiveSecondStallIsReported() {
        val r = v(prev = 10_000, now = 11_000, beat = 6_000)
        assertTrue(r is StallVerdict.Verdict.Stalled)
        assertEquals(5_000L, (r as StallVerdict.Verdict.Stalled).ms)
    }

    @Test fun twoDayPauseIsNotAStall() {
        val twoDays = 2 * 24 * 3600_000L
        assertEquals(
            StallVerdict.Verdict.ProcessPaused,
            v(prev = 1_000, now = 1_000 + twoDays, beat = 1_000),
        )
    }

    @Test fun pauseJustOverTheLimitIsAPause() {
        assertEquals(StallVerdict.Verdict.ProcessPaused, v(prev = 0, now = 5_001, beat = 0))
    }

    @Test fun checkExactlyAtTheLimitStillCountsAsRunning() {
        // 5,000 ms since the last check is the longest gap that still means the watcher ran.
        val r = v(prev = 0, now = 5_000, beat = 0)
        assertTrue(r is StallVerdict.Verdict.Stalled)
    }

    @Test fun stallStartingRightAfterAPauseIsSeenOnTheNextChecks() {
        // The caller resets lastBeat to 'now' on a pause (13,000); the screen thread then freezes.
        assertEquals(StallVerdict.Verdict.Ok, v(prev = 13_000, now = 14_000, beat = 13_000))
        assertEquals(StallVerdict.Verdict.Ok, v(prev = 14_000, now = 15_000, beat = 13_000))
        assertEquals(StallVerdict.Verdict.Ok, v(prev = 15_000, now = 16_000, beat = 13_000))
        val r = v(prev = 16_000, now = 17_000, beat = 13_000)
        assertTrue(r is StallVerdict.Verdict.Stalled)
        assertEquals(4_000L, (r as StallVerdict.Verdict.Stalled).ms)
    }
}
