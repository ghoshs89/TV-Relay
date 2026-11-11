package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBreaksTest {
    private val minute = 60_000L

    // ---------- Sleep timer ----------

    @Test
    fun sleepTimerOffNeverFires() {
        val t = SleepTimer(0, start = 1_000)
        assertFalse(t.enabled)
        assertEquals(SleepTimer.Event.None, t.tick(1_000 + 10 * 60 * minute))
        assertFalse(t.keepWatching(5_000))
    }

    @Test
    fun sleepTimerWarnsOnceAMinuteBeforeAndStopsOnceAtTheEnd() {
        val t = SleepTimer(30 * minute, start = 0)
        assertEquals(SleepTimer.Event.None, t.tick(10 * minute))
        assertEquals(SleepTimer.Event.None, t.tick(29 * minute - 1))
        assertEquals(SleepTimer.Event.Warn, t.tick(29 * minute))
        assertEquals("warns only once", SleepTimer.Event.None, t.tick(29 * minute + 30_000))
        assertEquals(SleepTimer.Event.Stop, t.tick(30 * minute))
        assertEquals("stops only once", SleepTimer.Event.None, t.tick(31 * minute))
    }

    @Test
    fun inputBeforeTheWarningDoesNotRestartTheTimer() {
        val t = SleepTimer(30 * minute, start = 0)
        assertFalse(t.keepWatching(10 * minute))
        assertEquals(SleepTimer.Event.Warn, t.tick(29 * minute))
    }

    @Test
    fun inputDuringTheWarningAddsAFullPeriodAndWarnsAgainLater() {
        val t = SleepTimer(30 * minute, start = 0)
        assertEquals(SleepTimer.Event.Warn, t.tick(29 * minute))
        assertTrue(t.keepWatching(29 * minute + 20_000))
        assertEquals(SleepTimer.Event.None, t.tick(30 * minute))
        assertEquals(SleepTimer.Event.None, t.tick(58 * minute))
        assertEquals(SleepTimer.Event.Warn, t.tick(58 * minute + 30_000))
        assertEquals(SleepTimer.Event.Stop, t.tick(59 * minute + 20_000))
    }

    @Test
    fun inputAfterTheStopDoesNothing() {
        val t = SleepTimer(30 * minute, start = 0)
        assertEquals(SleepTimer.Event.Stop, t.tick(31 * minute))
        assertFalse(t.keepWatching(31 * minute))
    }

    @Test
    fun sleepChoicesCycleAndUnknownValuesBecomeOff() {
        assertEquals(30, SleepTimer.next(0))
        assertEquals(60, SleepTimer.next(30))
        assertEquals(0, SleepTimer.next(120))
        assertEquals(0, SleepTimer.next(45))
        assertEquals(0, SleepTimer.clean(45))
        assertEquals("Off", SleepTimer.label(0))
        assertEquals("Off", SleepTimer.label(45))
        assertEquals("90 minutes", SleepTimer.label(90))
    }

    // ---------- Still watching ----------

    @Test
    fun turnedOffNeverAsks() {
        val w = StillWatching(enabled = false, start = 0)
        repeat(5) { assertFalse(w.onAutoAdvance(it.toLong())) }
        assertEquals(StillWatching.Event.None, w.tick(10 * 60 * minute, live = true, playing = true))
    }

    @Test
    fun asksAtTheThirdEpisodeThatStartedByItself() {
        val w = StillWatching(enabled = true, start = 0)
        assertFalse(w.onAutoAdvance(1))
        assertFalse(w.onAutoAdvance(2))
        assertTrue(w.onAutoAdvance(3))
        assertTrue(w.asking)
    }

    @Test
    fun aKeyPressBetweenEpisodesStartsTheCountAgain() {
        val w = StillWatching(enabled = true, start = 0)
        assertFalse(w.onAutoAdvance(1))
        assertFalse(w.onAutoAdvance(2))
        w.onInput(3)
        assertFalse(w.onAutoAdvance(4))
        assertFalse(w.onAutoAdvance(5))
        assertTrue(w.onAutoAdvance(6))
    }

    @Test
    fun keepWatchingClearsTheQuestionAndStartsFresh() {
        val w = StillWatching(enabled = true, start = 0)
        repeat(3) { w.onAutoAdvance(it.toLong()) }
        assertTrue(w.asking)
        w.answered(1_000)
        assertFalse(w.asking)
        assertFalse(w.onAutoAdvance(2_000))
        assertFalse(w.onAutoAdvance(3_000))
        assertTrue(w.onAutoAdvance(4_000))
    }

    @Test
    fun keyPressesDoNotAnswerTheQuestion() {
        val w = StillWatching(enabled = true, start = 0)
        repeat(3) { w.onAutoAdvance(it.toLong()) }
        w.onInput(500)
        assertTrue(w.asking)
    }

    @Test
    fun liveTvAsksAfterFourHoursWithoutAKeyPress() {
        val w = StillWatching(enabled = true, start = 0)
        val four = StillWatching.LIVE_IDLE_MS
        assertEquals(StillWatching.Event.None, w.tick(four - 1, live = true, playing = true))
        assertEquals(StillWatching.Event.Ask, w.tick(four, live = true, playing = true))
        assertTrue(w.asking)
        assertEquals("asks once", StillWatching.Event.None, w.tick(four + minute, live = true, playing = true))
    }

    @Test
    fun aKeyPressRestartsTheFourHours() {
        val w = StillWatching(enabled = true, start = 0)
        val four = StillWatching.LIVE_IDLE_MS
        w.onInput(four - minute)
        assertEquals(StillWatching.Event.None, w.tick(four, live = true, playing = true))
        assertEquals(StillWatching.Event.Ask, w.tick(2 * four - minute, live = true, playing = true))
    }

    @Test
    fun movieAndPausedPlaybackDoNotTriggerTheLiveRule() {
        val w = StillWatching(enabled = true, start = 0)
        val five = 5 * 60 * minute
        assertEquals(StillWatching.Event.None, w.tick(five, live = false, playing = true))
        assertEquals(StillWatching.Event.None, w.tick(five, live = true, playing = false))
    }

    @Test
    fun anUnansweredQuestionStopsPlaybackOnce() {
        val w = StillWatching(enabled = true, start = 0)
        repeat(3) { w.onAutoAdvance(1_000) }
        assertEquals(StillWatching.Event.None, w.tick(1_000 + StillWatching.ANSWER_MS - 1, live = false, playing = false))
        assertEquals(StillWatching.Event.Stop, w.tick(1_000 + StillWatching.ANSWER_MS, live = false, playing = false))
        assertEquals(StillWatching.Event.None, w.tick(1_000 + 2 * StillWatching.ANSWER_MS, live = false, playing = false))
    }
}
