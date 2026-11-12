package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyUsageTest {
    private val today = "2026-11-12"
    private val minute = 60_000L

    @Test
    fun readsTodaysMillisecondsAndIgnoresOtherDays() {
        assertEquals(5 * minute, DailyUsage.ms("$today|${5 * minute}", today))
        assertEquals(0L, DailyUsage.ms("2026-11-11|999999", today))
        assertEquals(0L, DailyUsage.ms(null, today))
    }

    @Test
    fun damagedValuesCountAsNothing() {
        assertEquals(0L, DailyUsage.ms("", today))
        assertEquals(0L, DailyUsage.ms("$today|", today))
        assertEquals(0L, DailyUsage.ms("$today|abc", today))
        assertEquals(0L, DailyUsage.ms("$today|-5", today))
        assertEquals(0L, DailyUsage.ms("nonsense", today))
    }

    @Test
    fun theLimitSeesTheSumAcrossScreens() {
        val others = mapOf("tv2" to "$today|${20 * minute}", "tv3" to "$today|${15 * minute}")
        assertEquals(60 * minute, DailyUsage.total("$today|${25 * minute}", others, today))
    }

    @Test
    fun yesterdayOnAnotherScreenDoesNotCount() {
        val others = mapOf("tv2" to "2026-11-11|${50 * minute}", "tv3" to "$today|${10 * minute}")
        assertEquals(15 * minute, DailyUsage.total("$today|${5 * minute}", others, today))
        assertEquals(mapOf("tv3" to "$today|${10 * minute}"), DailyUsage.todayOnly(others, today))
    }

    @Test
    fun theDayRollsOverToZero() {
        assertEquals(0L, DailyUsage.total("$today|${5 * minute}", mapOf("tv2" to "$today|1000"), "2026-11-13"))
    }

    @Test
    fun formatsWhatItReads() {
        assertEquals(1234L, DailyUsage.ms(DailyUsage.format(today, 1234), today))
    }
}
