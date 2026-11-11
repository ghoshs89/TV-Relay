package dev.relay.tv.player

import dev.relay.tv.data.KidsLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsLimitTrackerTest {
    private val saved = mutableListOf<Long>()
    private val warnings = mutableListOf<String>()
    private val stops = mutableListOf<String>()
    private var verdict: KidsLimits.Verdict = KidsLimits.Verdict.Allowed(null)
    private var verdictCalls = 0

    private fun tracker(enabled: Boolean = true) = KidsLimitTracker(
        enabled, { saved += it }, { verdictCalls++; verdict }, { warnings += it }, { stops += it },
    )

    /** Plays for [seconds] with a tick every second, starting at [start] ms. Returns the next start. */
    private fun play(t: KidsLimitTracker, seconds: Int, start: Long = 1_000L, playing: Boolean = true): Long {
        var now = start
        repeat(seconds) { t.tick(now, playing); now += 1_000 }
        return now
    }

    @Test
    fun grownUpProfilesAreNeverCountedOrChecked() {
        val t = tracker(enabled = false)
        play(t, 120)
        assertTrue(saved.isEmpty())
        assertEquals(0, verdictCalls)
        assertFalse(t.reached)
    }

    @Test
    fun timeIsSavedAndTheLimitCheckedAboutEveryThirtySecondsOfPlaying() {
        val t = tracker()
        play(t, 31)
        assertEquals(1, verdictCalls)
        assertEquals(1, saved.size)
        assertTrue("about 30 s counted", saved[0] in 29_000L..31_000L)
    }

    @Test
    fun pausedTimeIsNotCounted() {
        val t = tracker()
        val next = play(t, 20)
        play(t, 60, start = next, playing = false)
        assertTrue(saved.isEmpty())
        assertEquals(0, verdictCalls)
        t.flush()
        assertEquals("only the 19 s that were played", listOf(19_000L), saved)
        // Playing again starts a fresh stretch: the first tick only sets the start time.
        t.tick(500_000, true)
        t.tick(510_000, true)
        t.flush()
        assertEquals(listOf(19_000L, 10_000L), saved)
    }

    @Test
    fun aBlockedVerdictStopsPlaybackOnceWithTheReason() {
        verdict = KidsLimits.Verdict.Blocked("That's all the watching for today.")
        val t = tracker()
        play(t, 100)
        assertEquals(listOf("That's all the watching for today."), stops)
        assertTrue(t.reached)
        // Nothing more is counted once the limit is reached.
        val savedBefore = saved.size
        play(t, 100, start = 200_000)
        assertEquals(savedBefore, saved.size)
    }

    @Test
    fun fiveMinutesOrLessLeftWarnsOnce() {
        verdict = KidsLimits.Verdict.Allowed(4)
        val t = tracker()
        play(t, 200)
        assertEquals(listOf("4 minutes of watching left"), warnings)
    }

    @Test
    fun oneMinuteLeftHasItsOwnWording() {
        verdict = KidsLimits.Verdict.Allowed(1)
        val t = tracker()
        play(t, 40)
        assertEquals(listOf("About a minute of watching left"), warnings)
    }

    @Test
    fun moreThanFiveMinutesLeftOrNoLimitDoesNotWarn() {
        verdict = KidsLimits.Verdict.Allowed(6)
        val t = tracker()
        play(t, 100)
        verdict = KidsLimits.Verdict.Allowed(null)
        play(t, 100, start = 500_000)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun flushSavesWhatWasCountedAndOnlyOnce() {
        val t = tracker()
        play(t, 10)
        t.flush()
        t.flush()
        assertEquals(1, saved.size)
        assertTrue(saved[0] in 8_000L..10_000L)
    }
}
