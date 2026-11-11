package dev.relay.tv.data

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheLimitsTest {
    @Test
    fun pruneDropsTheOldestWhenOverTheLimit() {
        val m = ConcurrentHashMap<Int, Pair<Long, String>>()
        (1..10).forEach { m[it] = (it * 100L) to "v$it" }
        CacheLimits.prune(m, max = 10, drop = 4)
        assertEquals(10, m.size) // not over the limit: untouched
        m[11] = 1100L to "v11"
        CacheLimits.prune(m, max = 10, drop = 4)
        assertEquals(7, m.size)
        assertEquals(setOf(5, 6, 7, 8, 9, 10, 11), m.keys.toSet())
    }

    @Test
    fun failuresAreRememberedThenForgotten() {
        var now = 0L
        val grew = mutableListOf<Int>()
        val f = FailureMemory(max = 3, retryMs = 1000, clock = { now }, onGrew = { grew += it })
        assertFalse(f.has("a"))
        f.add("a")
        f.add("a")
        assertTrue(f.has("a"))
        assertEquals(listOf(1), grew)
        now = 1500
        assertFalse(f.has("a"))
        assertEquals(0, f.size)
    }

    @Test
    fun theOldestFailureIsDroppedAtTheLimit() {
        val f = FailureMemory(max = 3, retryMs = 1_000_000, clock = { 0L })
        listOf("a", "b", "c", "d").forEach { f.add(it) }
        assertEquals(3, f.size)
        assertFalse(f.has("a"))
        assertTrue(f.has("d"))
    }
}
