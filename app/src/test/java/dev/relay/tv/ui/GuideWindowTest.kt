package dev.relay.tv.ui

import dev.relay.tv.data.EpgEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideWindowTest {
    @Test fun guideRollsThroughMidnightAndLongSessions() {
        val midnight = 1_728_000L
        val before = GuideWindow.at(midnight - 1)
        val after = GuideWindow.at(midnight)
        assertEquals(before.start + 1800, after.start)
        assertEquals(before.end + 1800, after.end)
        assertEquals(after, GuideWindow.at(midnight + 1799))
        val later = GuideWindow.at(midnight + 8 * 3600)
        val upcoming = EpgEntry("Next show", "", later.end - 1800, later.end, false)
        assertFalse(after.shows(upcoming))
        assertTrue(later.shows(upcoming))
    }

    @Test fun aProgrammeRemainsFocusableUntilItAgesOut() {
        val now = 1_728_000L
        val before = GuideWindow.at(now)
        val after = GuideWindow.at(now + 1800)
        val old = EpgEntry("Past show", "", before.start, after.start, true)
        val across = old.copy(end = after.start + 1800)
        assertTrue(before.shows(old))
        assertTrue(after.expired(old))
        assertTrue(after.shows(across))
        assertFalse(after.expired(across))
    }
}
