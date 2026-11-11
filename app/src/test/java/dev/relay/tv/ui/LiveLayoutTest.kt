package dev.relay.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLayoutTest {
    private fun listShare(width: Float, side: Float): Float {
        val w = LiveLayout.widths(width, side)
        val usable = width - 2 * side
        return (usable - w.category - w.panel - 28f) / usable
    }

    @Test
    fun theChannelListKeepsAtLeastAboutHalfOnCommonScreens() {
        // 1080p TV, 4K TV scaled to 1280 dp, a wide 4K layout, and a phone in landscape.
        assertTrue(listShare(960f, 32f) >= 0.45f)
        assertTrue(listShare(1280f, 32f) >= 0.45f)
        assertTrue(listShare(1920f, 32f) >= 0.45f)
        assertTrue(listShare(750f, 16f) >= 0.449f)
    }

    @Test
    fun columnsStayWithinTheirLimits() {
        for (w in listOf(700f, 960f, 1280f, 2000f)) {
            val c = LiveLayout.widths(w, 32f)
            assertTrue(c.category in 150f..240f)
            assertTrue(c.panel in 200f..340f)
        }
    }

    @Test
    fun aVeryWideScreenUsesTheLargestColumns() {
        val c = LiveLayout.widths(3000f, 32f)
        assertEquals(240f, c.category, 0.01f)
        assertEquals(340f, c.panel, 0.01f)
    }
}
