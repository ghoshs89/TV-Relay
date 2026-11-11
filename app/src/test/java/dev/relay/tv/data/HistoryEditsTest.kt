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

    private fun p(key: String, kind: String, position: Long, duration: Long) =
        WatchEntry(key = key, kind = kind, title = key, streamId = key, ext = "mp4", position = position, duration = duration)

    @Test
    fun clearingContinueWatchingTakesOutOnlyTitlesInProgress() {
        val list = listOf(
            p("m:1", "movie", 600_000, 7_200_000),      // in progress
            p("ep:2", "episode", 120_000, 2_700_000),    // in progress
            p("m:3", "movie", 7_100_000, 7_200_000),     // finished: not in the row
            p("m:4", "movie", 10_000, 7_200_000),        // barely started: not in the row
            e("live:5", "live", "5"),                    // a channel
        )
        assertEquals(listOf("m:3", "m:4", "live:5"), HistoryEdits.withoutContinue(list).map { it.key })
        assertEquals(listOf("m:1", "ep:2"), HistoryEdits.continueEntries(list).map { it.key })
    }

    @Test
    fun clearingAnEmptyOrUnrelatedListChangesNothing() {
        assertEquals(emptyList<WatchEntry>(), HistoryEdits.withoutContinue(emptyList()))
        val list = listOf(e("live:1", "live", "1"))
        assertEquals(list, HistoryEdits.withoutContinue(list))
    }
}
