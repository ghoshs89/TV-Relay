package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStatsTest {
    @Test
    fun noFramesGivesNothing() {
        assertNull(FrameStats().take())
    }

    @Test
    fun countsFramesOverEachLimitAndFindsTheWorst() {
        val f = FrameStats()
        listOf(8f, 12f, 16f, 20f, 40f, 120f).forEach { f.add(it) }
        val s = f.take()!!
        assertEquals(6, s.frames)
        assertEquals(3, s.over16)
        assertEquals(2, s.over32)
        assertEquals(1, s.over100)
        assertEquals(120f, s.worst)
    }

    @Test
    fun takeStartsAgain() {
        val f = FrameStats()
        f.add(10f)
        f.take()
        assertNull(f.take())
        f.add(30f)
        assertEquals(1, f.take()!!.frames)
    }

    @Test
    fun ninetyFifthPercentileIgnoresTheSingleWorstOfTwenty() {
        val f = FrameStats()
        repeat(19) { f.add(10f) }
        f.add(90f)
        val s = f.take()!!
        assertEquals(10f, s.p95)
        assertEquals(90f, s.worst)
    }

    @Test
    fun textIsReadable() {
        val f = FrameStats()
        f.add(10f)
        assertEquals("[scroll] 1 frames, 0 over 16 ms, 0 over 32 ms, 0 over 100 ms, 95% under 10.0 ms, worst 10.0 ms", f.take()!!.text("scroll"))
    }
}
