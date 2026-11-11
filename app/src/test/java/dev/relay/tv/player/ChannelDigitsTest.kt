package dev.relay.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelDigitsTest {
    @Test
    fun pressingBuildsTheNumberAndTakeReturnsItOnce() {
        val d = ChannelDigits()
        assertEquals("1", d.press(1))
        assertEquals("12", d.press(2))
        assertEquals("120", d.press(0))
        assertEquals(120, d.take())
        assertNull(d.take())
    }

    @Test
    fun aSixthDigitStartsANewNumber() {
        val d = ChannelDigits()
        "12345".forEach { d.press(it - '0') }
        assertEquals("9", d.press(9))
        assertEquals(9, d.take())
    }

    @Test
    fun leadingZerosAreKeptAsTyped() {
        val d = ChannelDigits()
        d.press(0)
        assertEquals("07", d.press(7))
        assertEquals(7, d.take())
    }

    @Test
    fun takingWithNothingTypedGivesNull() {
        assertNull(ChannelDigits().take())
    }
}
