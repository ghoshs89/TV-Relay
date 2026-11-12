package dev.relay.tv.sync

import dev.relay.tv.data.SyncRec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncScheduleTest {

    @Test fun backoffIsOneFiveFifteen() {
        assertEquals(0L, SyncSchedule.backoffMs(0))
        assertEquals(60_000L, SyncSchedule.backoffMs(1))
        assertEquals(300_000L, SyncSchedule.backoffMs(2))
        assertEquals(900_000L, SyncSchedule.backoffMs(3))
        assertEquals(900_000L, SyncSchedule.backoffMs(40))
    }

    private fun decide(running: Boolean = false, parsing: Boolean = false, failures: Int = 0, lastFail: Long = 0, now: Long = 1_000_000, manual: Boolean = false) =
        SyncSchedule.decide(now, running, parsing, failures, lastFail, manual)

    @Test fun plainRunGoes() = assertEquals(SyncSchedule.Decision.Run, decide())

    @Test fun oneRunAtATime() {
        assertEquals(SyncSchedule.Decision.Busy, decide(running = true))
        assertEquals(SyncSchedule.Decision.Busy, decide(running = true, manual = true))
    }

    @Test fun waitsWhileTheLibraryIsRead() {
        assertEquals(SyncSchedule.Decision.Parsing, decide(parsing = true))
        assertEquals(SyncSchedule.Decision.Run, decide(parsing = true, manual = true))
    }

    @Test fun backoffAfterFailures() {
        assertEquals(SyncSchedule.Decision.Backoff, decide(failures = 1, lastFail = 1_000_000 - 30_000))
        assertEquals(SyncSchedule.Decision.Run, decide(failures = 1, lastFail = 1_000_000 - 60_000))
        assertEquals(SyncSchedule.Decision.Backoff, decide(failures = 3, lastFail = 1_000_000 - 600_000))
        assertEquals(SyncSchedule.Decision.Run, decide(failures = 3, lastFail = 1_000_000 - 900_000))
        assertEquals(SyncSchedule.Decision.Run, decide(failures = 3, lastFail = 1_000_000 - 1_000, manual = true))
    }

    @Test fun onlyTroubleThatMayPassIsRetried() {
        assertTrue(SyncSchedule.failure(SyncResult.Offline))
        assertTrue(SyncSchedule.failure(SyncResult.Refused))
        assertFalse(SyncSchedule.failure(SyncResult.UpToDate))
        assertFalse(SyncSchedule.failure(SyncResult.Both))
        assertFalse(SyncSchedule.failure(SyncResult.WrongKey))
    }

    // ---------- status text ----------

    @Test fun agoWords() {
        assertEquals("just now", SyncStatusText.ago(5_000))
        assertEquals("1 minute ago", SyncStatusText.ago(60_000))
        assertEquals("2 minutes ago", SyncStatusText.ago(150_000))
        assertEquals("1 hour ago", SyncStatusText.ago(3_600_000))
        assertEquals("5 hours ago", SyncStatusText.ago(5 * 3_600_000L + 1))
        assertEquals("1 day ago", SyncStatusText.ago(86_400_000L))
        assertEquals("3 days ago", SyncStatusText.ago(3 * 86_400_000L))
    }

    @Test fun statusLines() {
        val now = 10_000_000L
        assertEquals("Not synced yet", SyncStatusText.line(null, "", 0, now))
        assertEquals("In sync, 2 minutes ago", SyncStatusText.line(SyncResult.Pulled, "", now - 120_000, now))
        assertEquals("Waiting for the network, last in sync 1 hour ago", SyncStatusText.line(SyncResult.Offline, "", now - 3_600_000, now))
        assertEquals("Waiting for the network", SyncStatusText.line(SyncResult.Offline, "", 0, now))
        assertTrue(SyncStatusText.line(SyncResult.Refused, "", 0, now).contains("token was refused"))
        assertTrue(SyncStatusText.line(SyncResult.WrongAccount, "", 0, now).contains("different provider account"))
        assertTrue(SyncStatusText.line(SyncResult.NeedsUpdate, "", 0, now).startsWith("Update Relay"))
        assertEquals("Boom", SyncStatusText.line(SyncResult.Problem, "Boom", 0, now))
        assertTrue(SyncStatusText.line(SyncResult.Problem, "", 0, now).isNotBlank())
    }

    // ---------- local safety copies ----------

    @Test fun keepsTheNewestThreeBackups() {
        val names = listOf(1L, 5L, 3L, 9L, 7L).map { SyncBackups.fileName(it) } + listOf("notes.txt", "before-sync-x.json", "relay-backup.json")
        val gone = SyncBackups.toDelete(names)
        assertEquals(listOf(SyncBackups.fileName(3), SyncBackups.fileName(1)) + listOf("before-sync-x.json").filter { false }, gone.filter { it != "before-sync-x.json" })
        assertFalse("notes.txt" in gone)
        assertFalse("relay-backup.json" in gone)
        assertEquals(emptyList<String>(), SyncBackups.toDelete(listOf(SyncBackups.fileName(1), SyncBackups.fileName(2))))
    }

    // ---------- summary ----------

    private fun rec(dev: String, v: String? = "x") = SyncRec(1, dev, v?.let { JsonPrimitive(it) })

    @Test fun summaryCountsInPlainWords() {
        val merged = mapOf(
            "hist:p:a" to rec("d1"), "hist:p:b" to rec("d1"), "hist:p:c" to rec("d1"),
            "fav:p:movie:1" to rec("d1"), "set:audioLang" to rec("d2"), "set:autoNext" to rec("d1"),
            "screen:d1" to SyncRec(1, "d1", JsonObject(mapOf("name" to JsonPrimitive("Living room TV"), "seen" to JsonPrimitive(1)))),
            "order:profiles" to rec("d1"), "use:p:d1" to rec("d1"), "favorder:p" to rec("d1"),
        )
        val s = SyncSummary.describe(merged, emptyMap()) { dev -> if (dev == "d1") "Living room TV" else null }
        assertEquals("3 watched titles, 1 favourite, 2 settings from Living room TV", s)
    }

    @Test fun summarySingular() {
        val merged = mapOf("fav:p:movie:1" to rec("d1"))
        assertEquals("1 favourite", SyncSummary.describe(merged, emptyMap()) { null })
    }

    @Test fun summaryIgnoresBookkeeping() {
        val merged = mapOf("screen:d1" to rec("d1"), "order:profiles" to rec("d1"), "use:p:d1" to rec("d1"), "favorder:p" to rec("d1"))
        assertEquals("", SyncSummary.describe(merged, emptyMap()) { "x" })
    }

    @Test fun summaryCountsRemovalsAndLeavesUnchangedOut() {
        val before = mapOf("fav:p:movie:1" to rec("d1"), "fav:p:movie:2" to rec("d1"))
        val merged = mapOf("fav:p:movie:1" to rec("d2", null), "fav:p:movie:2" to rec("d1"))
        assertEquals("1 favourite from Other", SyncSummary.describe(merged, before) { "Other" })
    }
}
