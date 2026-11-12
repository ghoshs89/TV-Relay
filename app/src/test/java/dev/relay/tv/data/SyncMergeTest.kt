package dev.relay.tv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class SyncMergeTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val today = "2026-11-12"

    private fun p(s: String): JsonElement = JsonPrimitive(s)
    private fun rec(at: Long, dev: String, v: JsonElement?) = SyncRec(at, dev, v)
    private fun doc(account: String = "", vararg r: Pair<String, SyncRec>) = SyncDoc(account = account, recs = r.toMap())
    // The records in these tests have small made-up times, so "now" is early enough that nothing is old.
    private fun merge(a: SyncDoc, b: SyncDoc) = SyncMerge.merge(a, b, 1_000L, today)

    // ---------- Which record wins ----------

    @Test
    fun theLaterChangeWins() {
        val a = doc("", "k" to rec(10, "a", p("old")))
        val b = doc("", "k" to rec(20, "b", p("new")))
        assertEquals(p("new"), merge(a, b).recs["k"]?.v)
        assertEquals(p("new"), merge(b, a).recs["k"]?.v)
    }

    @Test
    fun itemsOnlyOneScreenHasAreKept() {
        val a = doc("", "x" to rec(1, "a", p("1")))
        val b = doc("", "y" to rec(2, "b", p("2")))
        val m = merge(a, b)
        assertEquals(setOf("x", "y"), m.recs.keys)
    }

    @Test
    fun aRemovalBeatsAnOlderAddAndLosesToANewerOne() {
        val removed = doc("", "k" to rec(20, "a", null))
        val oldAdd = doc("", "k" to rec(10, "b", p("v")))
        val newAdd = doc("", "k" to rec(30, "b", p("v2")))
        assertTrue(merge(removed, oldAdd).recs["k"]!!.gone)
        assertTrue(merge(oldAdd, removed).recs["k"]!!.gone)
        assertEquals(p("v2"), merge(removed, newAdd).recs["k"]?.v)
        assertEquals(p("v2"), merge(newAdd, removed).recs["k"]?.v)
    }

    @Test
    fun equalTimesAreSettledByTheScreenIdTheSameWayEitherWay() {
        val a = doc("", "k" to rec(10, "tv-a", p("A")))
        val b = doc("", "k" to rec(10, "tv-b", p("B")))
        assertEquals(p("B"), merge(a, b).recs["k"]?.v)
        assertEquals(p("B"), merge(b, a).recs["k"]?.v)
    }

    @Test
    fun onAnExactTieTheFullerValueKeepsAFieldAnOlderAppWouldDrop() {
        val full = JsonObject(mapOf("a" to JsonPrimitive(1), "newField" to JsonPrimitive("kept")))
        val stripped = JsonObject(mapOf("a" to JsonPrimitive(1)))
        val a = doc("", "k" to rec(10, "x", full))
        val b = doc("", "k" to rec(10, "x", stripped))
        assertEquals(full, merge(a, b).recs["k"]?.v)
        assertEquals(full, merge(b, a).recs["k"]?.v)
    }

    // ---------- Laws ----------

    @Test
    fun mergingACopyWithItselfChangesNothing() {
        val a = doc("acct", "k" to rec(10, "a", p("v")), "g" to rec(20, "a", null))
        assertEquals(a, merge(a, a))
    }

    @Test
    fun mergingTwiceGivesTheSameAsOnce() {
        val a = doc("", "k" to rec(10, "a", p("v")), "m" to rec(5, "a", p("m")))
        val b = doc("", "k" to rec(20, "b", p("w")), "n" to rec(6, "b", null))
        val once = merge(a, b)
        assertEquals(once, merge(once, b))
        assertEquals(once, merge(a, once))
        assertEquals(once, merge(once, once))
    }

    private fun randomDoc(rnd: Random, devs: List<String>): SyncDoc {
        val recs = HashMap<String, SyncRec>()
        repeat(rnd.nextInt(0, 12)) {
            val key = "k${rnd.nextInt(0, 8)}"
            val v: JsonElement? = if (rnd.nextInt(4) == 0) null else p("v${rnd.nextInt(0, 4)}")
            val candidate = SyncRec(now - rnd.nextLong(0, 5) * 1000, devs[rnd.nextInt(devs.size)], v)
            val mine = recs[key]
            if (mine == null || candidate.beats(mine)) recs[key] = candidate
        }
        return SyncDoc(recs = recs)
    }

    @Test
    fun randomCopiesMergeTheSameInEitherOrderAndAnyGrouping() {
        val rnd = Random(42)
        val devs = listOf("a", "b", "c")
        repeat(300) {
            val a = randomDoc(rnd, devs)
            val b = randomDoc(rnd, devs)
            val c = randomDoc(rnd, devs)
            assertEquals(merge(a, b), merge(b, a))
            assertEquals(merge(merge(a, b), c), merge(a, merge(b, c)))
            assertEquals(merge(merge(a, b), c), merge(merge(c, a), b))
            val m = merge(a, b)
            assertEquals(m, merge(m, a))
            assertEquals(m, merge(m, b))
        }
    }

    @Test
    fun twoScreensThatDivergedFromOneStartAgree() {
        val rnd = Random(7)
        repeat(200) {
            val start = randomDoc(rnd, listOf("s"))
            val a = merge(start, randomDoc(rnd, listOf("a")))
            val b = merge(start, randomDoc(rnd, listOf("b")))
            val ab = merge(a, b)
            val ba = merge(b, a)
            assertEquals(ab, ba)
            assertEquals(ab, merge(ab, ba))
        }
    }

    // ---------- Accounts ----------

    @Test
    fun differentAccountsRefuseToMerge() {
        val a = doc("aaaa")
        val b = doc("bbbb")
        try {
            merge(a, b)
            fail("expected WrongAccountException")
        } catch (e: WrongAccountException) {
            // expected
        }
    }

    @Test
    fun aBlankAccountTakesTheOthers() {
        assertEquals("aaaa", merge(doc(""), doc("aaaa")).account)
        assertEquals("aaaa", merge(doc("aaaa"), doc("")).account)
        assertEquals("aaaa", merge(doc("aaaa"), doc("aaaa")).account)
        assertEquals("", merge(doc(""), doc("")).account)
    }

    // ---------- Housekeeping ----------

    @Test
    fun oldRemovalsArePrunedButRecentOnesStay() {
        val a = doc(
            "",
            "old" to rec(now - SyncMerge.TOMBSTONE_MS - 1, "a", null),
            "recent" to rec(now - 10 * day, "a", null),
            "live" to rec(now - 400 * day, "a", p("v")),
        )
        val m = SyncMerge.tidy(a, now, today)
        assertNull(m.recs["old"])
        assertNotNull(m.recs["recent"])
        assertNotNull(m.recs["live"])
    }

    private fun use(day: String, ms: Long): JsonElement = JsonObject(mapOf("d" to JsonPrimitive(day), "ms" to JsonPrimitive(ms)))

    @Test
    fun onlyTodaysKidsTimeIsKept() {
        val a = doc(
            "",
            "use:p1:tv1" to rec(now, "tv1", use(today, 1000)),
            "use:p1:tv2" to rec(now, "tv2", use("2026-11-11", 9000)),
            "use:p1:tv3" to rec(now, "tv3", null),
        )
        val m = SyncMerge.tidy(a, now, today)
        assertEquals(setOf("use:p1:tv1"), m.recs.keys)
    }

    @Test
    fun aDeletedProfileTakesItsRecordsWithIt() {
        val a = doc(
            "",
            "profile:p1" to rec(now, "a", null),
            "profile:p2" to rec(now, "a", p("{}")),
            "hist:p1:movie:1" to rec(now, "a", p("h")),
            "fav:p1:live:2" to rec(now, "a", p("f")),
            "favorder:p1" to rec(now, "a", JsonArray(emptyList())),
            "allow:p1" to rec(now, "a", p("1")),
            "hist:p2:movie:1" to rec(now, "a", p("h")),
            "set:audioLang" to rec(now, "a", p("hi")),
        )
        val m = SyncMerge.tidy(a, now, today)
        assertEquals(setOf("profile:p1", "profile:p2", "hist:p2:movie:1", "set:audioLang"), m.recs.keys)
    }

    @Test
    fun eachProfileKeepsItsNewest400WatchedItems() {
        val recs = HashMap<String, SyncRec>()
        for (i in 0 until 450) recs["hist:p1:movie:$i"] = rec(now - i, "a", p("h$i"))
        for (i in 0 until 10) recs["hist:p2:movie:$i"] = rec(now - i, "a", p("h$i"))
        val m = SyncMerge.tidy(SyncDoc(recs = recs), now, today)
        assertEquals(400, m.recs.keys.count { it.startsWith("hist:p1:") })
        assertEquals(10, m.recs.keys.count { it.startsWith("hist:p2:") })
        assertNotNull(m.recs["hist:p1:movie:0"])
        assertNotNull(m.recs["hist:p1:movie:399"])
        assertNull(m.recs["hist:p1:movie:400"])
        assertNull(m.recs["hist:p1:movie:449"])
    }

    @Test
    fun theCapIsAppliedAfterMergingTwoLongHistories() {
        val a = HashMap<String, SyncRec>()
        val b = HashMap<String, SyncRec>()
        for (i in 0 until 400) a["hist:p1:a$i"] = rec(now - 2 * i, "a", p("x"))
        for (i in 0 until 400) b["hist:p1:b$i"] = rec(now - 2 * i - 1, "b", p("y"))
        val m = SyncMerge.merge(SyncDoc(recs = a), SyncDoc(recs = b), now, today)
        assertEquals(400, m.recs.size)
        assertEquals(m, SyncMerge.merge(SyncDoc(recs = b), SyncDoc(recs = a), now, today))
    }

    // ---------- The file ----------

    @Test
    fun theDocumentSurvivesWritingAndReading() {
        val d = SyncDoc(
            account = "acct",
            recs = mapOf(
                "k" to SyncRec(10, "a", JsonObject(mapOf("x" to JsonPrimitive(1)))),
                "gone" to SyncRec(11, "b", null),
            ),
        )
        assertEquals(d, SyncDoc.parse(d.encode()))
    }

    @Test
    fun fieldsFromANewerVersionAreCarriedThroughUnchanged() {
        val text = """
            {"app":"relay-sync","format":1,"account":"a","future":{"x":[1,2]},
             "recs":{"k":{"at":5,"dev":"d","v":"val","note":"added later"}}}
        """.trimIndent()
        val d = SyncDoc.parse(text)
        assertEquals(JsonObject(mapOf("x" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))), d.extra["future"])
        assertEquals(p("added later"), d.recs["k"]?.extra?.get("note"))
        val again = SyncDoc.parse(merge(d, SyncDoc(account = "a")).encode())
        assertEquals(d.extra["future"], again.extra["future"])
        assertEquals(p("added later"), again.recs["k"]?.extra?.get("note"))
    }

    @Test
    fun aHigherFormatNumberIsFlaggedAsNewer() {
        val d = SyncDoc.parse("""{"app":"relay-sync","format":${SyncDoc.FORMAT + 1},"account":"","recs":{}}""")
        assertTrue(d.newer)
        assertFalse(SyncDoc().newer)
    }

    @Test
    fun filesThatAreNotSyncFilesAreRefused() {
        for (bad in listOf("", "not json", "[]", """{"app":"other","format":1}""", """{"app":"relay-sync"}""")) {
            try {
                SyncDoc.parse(bad)
                fail("expected an exception for: $bad")
            } catch (e: SyncDocException) {
                // expected
            }
        }
    }

    @Test
    fun countsTheDifferencesBetweenTwoCopies() {
        val a = doc("", "x" to rec(1, "a", p("1")), "y" to rec(2, "a", p("2")))
        assertEquals(0, SyncMerge.differences(a, a))
        assertEquals(1, SyncMerge.differences(a, doc("", "x" to rec(1, "a", p("1")))))
        assertEquals(2, SyncMerge.differences(a, doc("", "x" to rec(9, "a", p("1")), "z" to rec(1, "a", p("z")))) - 1)
        assertEquals(SyncMerge.latest(a), 2L)
    }
}
