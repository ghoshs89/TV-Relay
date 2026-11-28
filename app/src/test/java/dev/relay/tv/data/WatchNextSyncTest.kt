package dev.relay.tv.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextSyncTest {
    private val p = Profile("p1", "Me")

    @Test
    fun dismissalsTravelAsTheirOwnRecordsAndComeBack() {
        val d = SyncData(profiles = listOf(p), dismissed = mapOf("p1" to mapOf("notint:tmdb:m:1" to 50L, "upnext:7" to 60L)))
        val recs = SyncCodec.toRecords(d, "dev", "2026-11-28", 1000)
        assertEquals(JsonPrimitive(50L), recs["dis:p1:notint:tmdb:m:1"])
        val back = SyncCodec.fromRecords(recs.mapValues { SyncRec(1, "dev", it.value) }, SyncData(), "dev", "2026-11-28")
        assertEquals(d.dismissed, back.dismissed)
    }

    @Test
    fun anOlderScreenThatDoesNotKnowDismissalsNeverRemovesThem() {
        // A 1.0.144 screen applies the file without the records and rebases on what it holds: no baseline entry.
        val merged = mapOf("dis:p1:upnext:7" to SyncRec(60, "new", JsonPrimitive(60L)))
        val base = SyncDiff.rebaseChecked(merged, after = emptyMap(), intended = emptyMap(), old = emptyMap())
        assertTrue(base.isEmpty())
        // Its next export therefore holds nothing for that item, and the merge keeps the newer screen's record.
        val exported = SyncDiff.export(emptyMap(), base, "old", SyncClock(), 100, changedAt = { _, _ -> null })
        assertTrue(exported.isEmpty())
        val kept = SyncMerge.union(SyncDoc(recs = merged), SyncDoc(recs = exported))
        assertEquals(merged, kept.recs)
    }

    @Test
    fun dismissalsAreCappedAndMergedByTime() {
        var m = emptyMap<String, Long>()
        repeat(Dismissals.MAX + 10) { m = Dismissals.add(m, "k$it", it.toLong()) }
        assertEquals(Dismissals.MAX, m.size)
        assertTrue("k0" !in m)
        assertEquals(mapOf("a" to 5L, "b" to 2L), Dismissals.merge(mapOf("a" to 1L, "b" to 2L), mapOf("a" to 5L)))
    }

    @Test
    fun backupsWithoutDismissalsStillRead() {
        val full = Jsons.json.parseToJsonElement(Backups.encode(BackupFile(profiles = listOf(p)))) as kotlinx.serialization.json.JsonObject
        val b = Backups.decode(kotlinx.serialization.json.JsonObject(full - "dismissed").toString())
        assertTrue(b.dismissed.isEmpty())
        val withThem = Backups.decode(Backups.encode(BackupFile(profiles = listOf(p), dismissed = mapOf("p1" to mapOf("upnext:7" to 1L)))))
        assertEquals(1L, withThem.dismissed["p1"]?.get("upnext:7"))
    }

    @Test
    fun watchNextRowsAreAddedOnceAfterContinueWatching() {
        val saved = listOf(HomeLayout.FAV_CHANNELS, HomeLayout.CONTINUE, HomeLayout.MY_LIST)
        val next = HomeLayout.withWatchNext(saved)
        assertEquals(listOf(HomeLayout.FAV_CHANNELS, HomeLayout.CONTINUE) + HomeLayout.WATCH_NEXT + HomeLayout.MY_LIST, next)
        assertEquals(next, HomeLayout.withWatchNext(next))
        assertEquals(HomeLayout.WATCH_NEXT + HomeLayout.MY_LIST, HomeLayout.withWatchNext(listOf(HomeLayout.MY_LIST)))
        assertTrue(HomeLayout.defaults(emptyList()).indexOf(HomeLayout.UP_NEXT) == 1)
    }
}
