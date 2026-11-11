package dev.relay.tv.ui

import dev.relay.tv.ui.OkPressTracker.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LongPressTest {
    @Before
    fun clean() = LongPressGuard.reset()

    @Test
    fun aShortPressClicksOnKeyUp() {
        val t = OkPressTracker()
        assertEquals(Result.Nothing, t.down(0))
        assertEquals(Result.Click, t.up())
    }

    @Test
    fun holdingFiresOnceAtTheFirstRepeatAndDoesNotClickOnRelease() {
        val t = OkPressTracker()
        assertEquals(Result.Nothing, t.down(0))
        assertEquals(Result.LongClick, t.down(1))
        assertEquals(Result.Nothing, t.down(2))
        assertEquals(Result.Nothing, t.down(3))
        assertEquals(Result.Nothing, t.up())
    }

    @Test
    fun repeatsFromAPressThisItemNeverSawAreIgnored() {
        val t = OkPressTracker()
        assertEquals(Result.Nothing, t.down(4))
        assertEquals(Result.Nothing, t.up())
    }

    @Test
    fun aKeyUpWithoutAKeyDownDoesNothing() {
        assertEquals(Result.Nothing, OkPressTracker().up())
    }

    @Test
    fun theNextPressStartsFresh() {
        val t = OkPressTracker()
        t.down(0); t.down(1); t.up()
        assertEquals(Result.Nothing, t.down(0))
        assertEquals(Result.Click, t.up())
        assertEquals(Result.Nothing, t.down(0))
        assertEquals(Result.LongClick, t.down(1))
    }

    @Test
    fun aLostKeyUpDoesNotBlockTheNextPress() {
        val t = OkPressTracker()
        t.down(0); t.down(1)
        // No key-up arrived. The next press is a normal one.
        assertEquals(Result.Nothing, t.down(0))
        assertEquals(Result.Click, t.up())
    }

    @Test
    fun guardDropsTheRestOfTheHoldAndItsKeyUp() {
        LongPressGuard.arm(23)
        assertTrue(LongPressGuard.swallows(23, 0, 3))
        assertTrue(LongPressGuard.swallows(23, 0, 4))
        assertTrue(LongPressGuard.swallows(23, 1, 0))
        // Released: the next press goes through.
        assertFalse(LongPressGuard.swallows(23, 0, 0))
        assertFalse(LongPressGuard.swallows(23, 1, 0))
    }

    @Test
    fun guardLetsOtherKeysThrough() {
        LongPressGuard.arm(23)
        assertFalse(LongPressGuard.swallows(20, 0, 0))
        assertFalse(LongPressGuard.swallows(4, 1, 0))
        assertTrue(LongPressGuard.swallows(23, 1, 0))
    }

    @Test
    fun guardStopsHoldingAfterALostKeyUpWhenANewPressStarts() {
        LongPressGuard.arm(66)
        assertFalse(LongPressGuard.swallows(66, 0, 0))
        assertFalse(LongPressGuard.swallows(66, 1, 0))
    }

    @Test
    fun unarmedGuardSwallowsNothing() {
        assertFalse(LongPressGuard.swallows(23, 0, 2))
        assertFalse(LongPressGuard.swallows(23, 1, 0))
    }
}
