package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferPlanTest {
    @Test
    fun liveStartsSoonerThanMovies() {
        val live = BufferPlan.forLive(true)
        val movie = BufferPlan.forLive(false)
        assertEquals(1_000, live.startMs)
        assertEquals(3_000, live.afterStallMs)
        assertEquals(2_000, movie.startMs)
        assertEquals(4_000, movie.afterStallMs)
        assertTrue(live.startMs < movie.startMs && live.afterStallMs < movie.afterStallMs)
    }

    @Test
    fun bothKeepTheSameReadAhead() {
        assertEquals(BufferPlan.forLive(true).minMs, BufferPlan.forLive(false).minMs)
        assertEquals(BufferPlan.forLive(true).maxMs, BufferPlan.forLive(false).maxMs)
    }

    @Test
    fun theStartBufferIsNeverLargerThanTheMinimumReadAhead() {
        // DefaultLoadControl rejects a playback buffer larger than the minimum buffer.
        for (live in listOf(true, false)) {
            val p = BufferPlan.forLive(live)
            assertTrue(p.startMs <= p.minMs && p.afterStallMs <= p.minMs)
        }
    }
}
