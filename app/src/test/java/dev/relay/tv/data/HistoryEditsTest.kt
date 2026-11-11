package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryEditsTest {
    private fun e(key: String, kind: String = "movie", streamId: String = key) =
        WatchEntry(key = key, kind = kind, title = key, streamId = streamId, ext = "mp4")

    @Test
    fun removingAKeyDropsOnlyThatEntry() {
        val list = listOf(e("m:1"), e("m:2"), e("ep:3", "episode"))
        assertEquals(listOf("m:1", "ep:3"), HistoryEdits.without(list, "m:2").map { it.key })
    }

    @Test
    fun anUnknownKeyChangesNothing() {
        val list = listOf(e("m:1"), e("m:2"))
        assertEquals(list, HistoryEdits.without(list, "m:9"))
    }

    @Test
    fun removingAChannelDropsEveryLiveEntryForThatStreamOnly() {
        val list = listOf(
            e("live:7", "live", "7"), e("live:7b", "live", "7"), e("live:8", "live", "8"),
            e("m:7", "movie", "7"),
        )
        assertEquals(listOf("live:8", "m:7"), HistoryEdits.withoutChannel(list, "7").map { it.key })
    }
}
