package dev.relay.tv.data

import dev.relay.tv.data.KidsLimits.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsLimitsTest {

    private val now = 1_000_000_000L
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    private fun check(limit: Int = 0, bed: String = "", wake: String = "", usedMin: Int = 0, nowMin: Int = at(16), allowUntil: Long = 0, kids: Boolean = true) =
        KidsLimits.check(kids, limit, bed, wake, usedMin * 60_000L, nowMin, now, allowUntil)

    @Test
    fun grownUpsAndUnlimitedKidsAreNeverBlocked() {
        assertEquals(Verdict.Allowed(null), check(limit = 30, usedMin = 500, kids = false))
        assertEquals(Verdict.Allowed(null), check())
    }

    @Test
    fun dailyLimitCountsDown() {
        assertEquals(Verdict.Allowed(20), check(limit = 60, usedMin = 40))
        assertTrue(check(limit = 60, usedMin = 60) is Verdict.Blocked)
        assertTrue(check(limit = 60, usedMin = 75) is Verdict.Blocked)
    }

    @Test
    fun bedtimeAcrossMidnight() {
        assertTrue(KidsLimits.quietNow(at(21), "20:30", "07:00"))
        assertTrue(KidsLimits.quietNow(at(2), "20:30", "07:00"))
        assertFalse(KidsLimits.quietNow(at(7), "20:30", "07:00"))
        assertFalse(KidsLimits.quietNow(at(20, 29), "20:30", "07:00"))
        assertTrue(KidsLimits.quietNow(at(6, 59), "20:30", ""))
        assertFalse(KidsLimits.quietNow(at(12), "", "07:00"))
        // Bedtime after midnight.
        assertTrue(KidsLimits.quietNow(at(1, 30), "01:00", "07:00"))
        assertFalse(KidsLimits.quietNow(at(23), "01:00", "07:00"))
    }

    @Test
    fun timeLeftIsTheSoonerOfTheLimitAndBedtime() {
        assertEquals(Verdict.Allowed(30), check(limit = 120, bed = "20:30", nowMin = at(20)))
        assertEquals(Verdict.Allowed(10), check(limit = 120, usedMin = 110, bed = "20:30", nowMin = at(18)))
        assertEquals(Verdict.Allowed(45), check(bed = "20:30", nowMin = at(19, 45)))
        val b = check(bed = "20:30", wake = "07:30", nowMin = at(22)) as Verdict.Blocked
        assertTrue(b.reason.contains("07:30"))
    }

    @Test
    fun aParentsExtraTimeLiftsBothLimits() {
        val until = now + 30 * 60_000L
        assertEquals(Verdict.Allowed(30), check(limit = 30, usedMin = 90, bed = "20:00", nowMin = at(21), allowUntil = until))
        assertTrue(check(limit = 30, usedMin = 90, allowUntil = now - 1) is Verdict.Blocked)
    }

    @Test
    fun parsingAndLabels() {
        assertEquals(1230, KidsLimits.minutesOf("20:30"))
        assertEquals(null, KidsLimits.minutesOf("25:00"))
        assertEquals(null, KidsLimits.minutesOf("soon"))
        assertEquals("1 h 30 min", KidsLimits.label(90))
        assertEquals("2 h", KidsLimits.label(120))
        assertEquals("45 min", KidsLimits.label(45))
        assertEquals("1 h a day · bedtime 20:30 to 07:00", KidsLimits.describe(60, "20:30", ""))
        assertEquals(30, KidsLimits.next(KidsLimits.DAILY_CHOICES, 0))
        assertEquals(0, KidsLimits.next(KidsLimits.DAILY_CHOICES, 180))
    }
}
