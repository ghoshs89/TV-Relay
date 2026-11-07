package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {

    private var now = 1_000L
    private val clock = { now }
    private val MB = 1024L * 1024

    @Test
    fun errorsMoveThroughServersThenGiveUpAfterThreeRounds() {
        val p = RecorderPolicy(hostCount = 2, clock = clock)
        val seen = mutableListOf<Int>()
        repeat(5) {
            assertEquals(RecorderPolicy.Decision.NextHost, p.onEnded(0, 500, error = true))
            seen += p.host
        }
        assertEquals(listOf(1, 0, 1, 0, 1), seen)
        assertTrue(p.onEnded(0, 500, error = true) is RecorderPolicy.Decision.GiveUp)
    }

    @Test
    fun cleanCloseWithLittleDataCountsAsFailure() {
        val p = RecorderPolicy(hostCount = 1, clock = clock)
        // Empty 200, then a few KB: both are failures even without an error.
        assertEquals(RecorderPolicy.Decision.NextHost, p.onEnded(0, 100, error = false))
        assertEquals(RecorderPolicy.Decision.NextHost, p.onEnded(8 * 1024, 300, error = false))
        assertTrue(p.onEnded(0, 100, error = false) is RecorderPolicy.Decision.GiveUp)
    }

    @Test
    fun repeatedQuickDisconnectsCountAsFailure() {
        val p = RecorderPolicy(hostCount = 2, clock = clock)
        assertEquals(RecorderPolicy.Decision.Retry, p.onEnded(2 * MB, 5_000, false))
        now += 5_000
        assertEquals(RecorderPolicy.Decision.Retry, p.onEnded(2 * MB, 5_000, false))
        now += 5_000
        assertEquals(RecorderPolicy.Decision.NextHost, p.onEnded(2 * MB, 5_000, false))
        assertEquals(1, p.failures)
    }

    @Test
    fun quickDisconnectsFarApartAreTolerated() {
        val p = RecorderPolicy(hostCount = 1, clock = clock)
        repeat(6) {
            assertEquals(RecorderPolicy.Decision.Retry, p.onEnded(2 * MB, 5_000, false))
            now += 40_000
        }
        assertEquals(0, p.failures)
    }

    @Test
    fun aLongHealthyConnectionClearsEarlierFailures() {
        val p = RecorderPolicy(hostCount = 1, clock = clock)
        p.onEnded(0, 100, true)
        p.onEnded(0, 100, true)
        assertEquals(2, p.failures)
        assertEquals(RecorderPolicy.Decision.Retry, p.onEnded(500 * MB, 600_000, false))
        assertEquals(0, p.failures)
    }

    @Test
    fun watchdogFiresOnlyAfterTheLimit() {
        val w = StallWatchdog(20_000, clock)
        assertFalse(w.tick(true))
        now += 19_000
        assertFalse(w.tick(true))
        now += 1_000
        assertTrue(w.tick(true))
        // Counting starts again after firing.
        now += 1_000
        assertFalse(w.tick(true))
    }

    @Test
    fun watchdogIgnoresPausesAndRecoveries() {
        val w = StallWatchdog(20_000, clock)
        w.tick(true)
        now += 15_000
        assertFalse(w.tick(false))
        now += 10_000
        assertFalse(w.tick(true))
        now += 10_000
        assertFalse(w.tick(true))
    }

    @Test
    fun hlsNeverUsesThePauseBuffer() {
        assertFalse(TimeshiftSupport.supports(listOf("http://a/live/u/p/1.m3u8")))
        assertFalse(TimeshiftSupport.supports(listOf("http://a/live/u/p/1.ts", "http://b/live/u/p/1.m3u8?token=x")))
        assertFalse(TimeshiftSupport.supports(emptyList()))
        assertTrue(TimeshiftSupport.supports(listOf("http://a/live/u/p/1.ts", "http://b/live/u/p/1.ts")))
    }

    @Test
    fun playlistBodyIsRecognised() {
        val pl = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
        assertTrue(TimeshiftSupport.looksLikePlaylist(pl, pl.size))
        val ts = ByteArray(188).also { it[0] = 0x47 }
        assertFalse(TimeshiftSupport.looksLikePlaylist(ts, ts.size))
    }
}
