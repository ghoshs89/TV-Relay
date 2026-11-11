package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LazyItemsTest {
    private fun ch(id: Int, num: Int) = Channel(id, num, "Ch $id", null, "c", null, 0)
    private val channels = (1..1000).map { ch(it, if (it % 10 == 0) it / 10 else 0) }

    @Test
    fun buildsOnlyWhatIsAskedFor() {
        var made = 0
        val items = LazyItems(channels) { made++; PlayItem("live:${it.id}", "live", it.name, streamId = it.id.toString(), ext = "ts") }
        assertEquals(1000, items.size)
        assertEquals(0, made)
        assertEquals("live:5", items[4].key)
        assertEquals("live:5", items[4].key)
        assertEquals(1, made)
        assertEquals(listOf("live:3", "live:4"), items.subList(2, 4).map { it.key })
        assertEquals(3, made)
    }

    @Test
    fun replacingOneEntryKeepsTheRest() {
        val items = LazyItems(channels) { PlayItem("live:${it.id}", "live", it.name, streamId = it.id.toString(), ext = "ts") }
        val swapped = PlayItem("live:99", "live", "Other", streamId = "99", ext = "ts")
        val next = items.with(2, swapped)
        assertSame(swapped, next[2])
        assertEquals("live:2", next[1].key)
        assertEquals("live:3", items[2].key)
    }

    @Test
    fun findsNumbersAndIdsWithoutBuilding() {
        var made = 0
        val items = LazyItems(channels) { made++; PlayItem("live:${it.id}", "live", it.name, streamId = it.id.toString(), ext = "ts") }
        assertEquals(19, items.indexOfNumber(2))
        assertEquals(-1, items.indexOfNumber(0))
        assertEquals(-1, items.indexOfNumber(5000))
        assertEquals(41, items.indexOfChannel(42))
        assertEquals(0, made)
    }
}
