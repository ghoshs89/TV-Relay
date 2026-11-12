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

    @Test
    fun onAFastScreenFramesOverTwoRefreshesAreCountedAsLate() {
        val f = FrameStats(framePeriodMs = 1000f / 120f)
        listOf(8f, 12f, 16f, 20f, 40f, 120f).forEach { f.add(it) }
        val s = f.take()!!
        // Over 16.7 ms on a 120 Hz screen: 20, 40 and 120.
        assertEquals(3, s.late)
        assertEquals(2, s.over32)
        org.junit.Assert.assertTrue(s.text("x").endsWith(", 3 late for a 120 Hz screen"))
    }

    @Test
    fun atSixtyHertzTheLineIsWhatTheBenchmarkReportReads() {
        val f = FrameStats()
        listOf(8f, 40f).forEach { f.add(it) }
        val s = f.take()!!
        assertEquals(s.over32, s.late)
        assertEquals("[x] 2 frames, 1 over 16 ms, 1 over 32 ms, 0 over 100 ms, 95% under 8.0 ms, worst 40.0 ms", s.text("x"))
    }
}
