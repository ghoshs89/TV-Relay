package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierTest {
    /** The numbers every device used before tiers existed. If one changes here, a TV changes: do it on purpose. */
    @Test
    fun standardTierIsExactlyWhatTvsHaveAlwaysUsed() {
        val t = DeviceTier.STANDARD
        assertEquals(3, t.parallelLists)
        assertEquals(350L, t.rowGuideDelayMs)
        assertEquals(120L, t.gridRowDelayMs)
        assertEquals(3, t.rowGuideRequests)
        assertEquals(4, t.gridGuideRequests)
        assertEquals(250L, t.findPauseMs)
        assertEquals(350L, t.searchPauseMs)
        assertEquals(8, t.imageRequests)
        assertEquals(4, t.imageRequestsPerHost)
        assertFalse(t.posterFade)
        assertEquals(Freshness.START_DELAY_MS, t.refreshStartDelayMs)
        assertEquals(5_000L, t.snapshotDelayMs)
        assertEquals(0, t.minTouchDp)
        assertEquals(16.7f, t.framePeriodMs, 0.1f)
    }

    @Test
    fun theTvFromTheLogGetsTheStandardTier() {
        // onn 4K Plus: 4 cores, 384 MB app limit, 60 Hz.
        val t = DeviceTier.of(cores = 4, memoryLimitMb = 384, lowRam = false, tv = true, refreshHz = 60f)
        assertEquals(DeviceTier.STANDARD, t)
    }

    @Test
    fun aTvIsNeverFastHoweverManyCoresItHas() {
        val t = DeviceTier.of(cores = 8, memoryLimitMb = 512, lowRam = false, tv = true, refreshHz = 120f)
        assertFalse(t.fast)
        assertEquals(DeviceTier.STANDARD.copy(framePeriodMs = t.framePeriodMs), t)
    }

    @Test
    fun aRecentPhoneIsFast() {
        val t = DeviceTier.of(cores = 8, memoryLimitMb = 512, lowRam = false, tv = false, refreshHz = 120f)
        assertTrue(t.fast)
        assertEquals(6, t.parallelLists)
        assertEquals(8.3f, t.framePeriodMs, 0.1f)
        assertEquals(DeviceTier.MIN_TOUCH_DP, t.minTouchDp)
        assertTrue(t.rowGuideDelayMs < DeviceTier.STANDARD.rowGuideDelayMs)
        assertTrue(t.refreshStartDelayMs < DeviceTier.STANDARD.refreshStartDelayMs)
    }

    @Test
    fun aWeakPhoneKeepsTheStandardWaitsButGetsTouchSizes() {
        for (t in listOf(
            DeviceTier.of(cores = 4, memoryLimitMb = 512, lowRam = false, tv = false, refreshHz = 60f),
            DeviceTier.of(cores = 8, memoryLimitMb = 192, lowRam = false, tv = false, refreshHz = 60f),
            DeviceTier.of(cores = 8, memoryLimitMb = 512, lowRam = true, tv = false, refreshHz = 60f),
        )) {
            assertFalse(t.fast)
            assertEquals(DeviceTier.STANDARD.copy(minTouchDp = DeviceTier.MIN_TOUCH_DP), t)
        }
    }

    @Test
    fun twoCoreDevicesReadTwoListsAtOnceAsBefore() {
        assertEquals(2, DeviceTier.of(cores = 2, memoryLimitMb = 256, lowRam = true, tv = true, refreshHz = 60f).parallelLists)
    }

    @Test
    fun fastTierLeavesTwoCoresFreeAndStopsAtSix() {
        assertEquals(4, DeviceTier.fast(cores = 6, refreshHz = 60f).parallelLists)
        assertEquals(6, DeviceTier.fast(cores = 12, refreshHz = 60f).parallelLists)
    }

    @Test
    fun aNonsenseRefreshRateCountsAsSixtyHertz() {
        assertEquals(16.7f, DeviceTier.standard(refreshHz = 0f).framePeriodMs, 0.1f)
        assertEquals(16.7f, DeviceTier.standard(refreshHz = Float.NaN).framePeriodMs, 0.1f)
    }
}
