package dev.relay.tv.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiffTest {
    private fun p(s: String): JsonElement = JsonPrimitive(s)
    private val now = 1_000_000L

    private fun export(
        snapshot: Map<String, JsonElement>,
        base: Map<String, BaseRec> = emptyMap(),
        clock: SyncClock = SyncClock(),
        at: Map<String, Long> = emptyMap(),
        defaults: Set<String> = emptySet(),
    ) = SyncDiff.export(
        snapshot, base, "me", clock, now,
        changedAt = { k, _ -> at[k] },
        isDefault = { k, _ -> k in defaults },
    )

    private fun base(at: Long, v: JsonElement?, dev: String = "other") = BaseRec(at, dev, v?.let { SyncDiff.hash(it) })

    @Test
    fun anUnchangedItemKeepsItsAgreedTimeAndOwner() {
        val r = export(mapOf("k" to p("v")), mapOf("k" to base(500, p("v"))))
        assertEquals(SyncRec(500, "other", p("v")), r["k"])
    }

    @Test
    fun aChangedItemIsStampedWithThisScreenAndTheTimeItWasChanged() {
        val r = export(mapOf("k" to p("new")), mapOf("k" to base(500, p("old"))), at = mapOf("k" to 900L))
        assertEquals(SyncRec(900, "me", p("new")), r["k"])
    }

    @Test
    fun aChangeIsAlwaysLaterThanTheVersionItReplacesEvenWithASlowClock() {
        val r = export(mapOf("k" to p("new")), mapOf("k" to base(5_000, p("old"))), at = mapOf("k" to 100L))
        assertEquals(5_001L, r["k"]!!.at)
    }

    @Test
    fun aChangeWithNoKnownTimeUsesTheClock() {
        val clock = SyncClock()
        val r = export(mapOf("a" to p("1"), "b" to p("2")), clock = clock)
        assertEquals(setOf(now, now + 1), r.values.map { it.at }.toSet())
        assertTrue(clock.last >= now + 1)
    }

    @Test
    fun anItemThatWasAgreedAndIsGoneBecomesARemovalLaterThanTheOriginal() {
        val r = export(emptyMap(), mapOf("k" to base(500, p("v"))), at = mapOf("k" to 400L))
        assertTrue(r["k"]!!.gone)
        assertEquals("me", r["k"]!!.dev)
        assertEquals(501L, r["k"]!!.at)
    }

    @Test
    fun theTimeAnItemWasRemovedIsUsedWhenKnown() {
        val r = export(emptyMap(), mapOf("k" to base(500, p("v"))), at = mapOf("k" to 800L))
        assertEquals(800L, r["k"]!!.at)
    }

    @Test
    fun anAgreedRemovalIsCarriedUnchanged() {
        val r = export(emptyMap(), mapOf("k" to BaseRec(700, "other", null)))
        assertEquals(SyncRec(700, "other", null), r["k"])
    }

    @Test
    fun addingBackAnItemAfterItsRemovalIsLaterThanTheRemoval() {
        val r = export(mapOf("k" to p("v")), mapOf("k" to BaseRec(9_000, "other", null)), at = mapOf("k" to 100L))
        assertEquals("me", r["k"]!!.dev)
        assertEquals(9_001L, r["k"]!!.at)
        assertEquals(p("v"), r["k"]!!.v)
    }

    @Test
    fun theLoginIsNeverRemovedBecauseThisScreenSignedOut() {
        val r = export(emptyMap(), mapOf("account" to base(500, p("acct"))))
        assertNull(r["account"])
    }

    @Test
    fun kidsTimeFromAnEarlierDayIsDroppedWithoutARemoval() {
        val r = export(emptyMap(), mapOf("use:p1:tv2" to base(500, p("x"))))
        assertNull(r["use:p1:tv2"])
    }

    @Test
    fun aSettingStillAtItsFactoryValueNeverBeatsAnotherScreensChoice() {
        val r = export(mapOf("set:audioLang" to p("en"), "set:hideAdult" to p("true")), defaults = setOf("set:audioLang"))
        assertEquals(0L, r["set:audioLang"]!!.at)
        assertTrue(r["set:hideAdult"]!!.at > 0)
    }

    @Test
    fun aSettingChangedBackToItsFactoryValueStillCountsAsAChange() {
        val r = export(
            mapOf("set:audioLang" to p("en")), mapOf("set:audioLang" to base(500, p("hi"))),
            at = mapOf("set:audioLang" to 900L), defaults = setOf("set:audioLang"),
        )
        assertEquals(900L, r["set:audioLang"]!!.at)
    }

    // ---------- What the screen holds after applying ----------

    @Test
    fun whatIsHeldAfterwardsUsesTheStoredValueNotTheDocumentsCopy() {
        val merged = mapOf(
            "k" to SyncRec(10, "a", p("from the document with extra fields")),
            "gone" to SyncRec(11, "b", null),
            "unknown-kind" to SyncRec(12, "c", p("x")),
        )
        val after = mapOf<String, JsonElement>("k" to p("as stored here"))
        val b = SyncDiff.rebase(merged, after)
        assertEquals(BaseRec(10, "a", SyncDiff.hash(p("as stored here"))), b["k"])
        assertEquals(BaseRec(11, "b", null), b["gone"])
        assertNull(b["unknown-kind"])
    }

    @Test
    fun afterApplyingNothingLooksChangedOnTheNextExport() {
        val merged = mapOf("k" to SyncRec(10, "a", p("doc")))
        val stored = mapOf<String, JsonElement>("k" to p("stored"))
        val b = SyncDiff.rebase(merged, stored)
        val next = export(stored, b)
        assertEquals(10L, next["k"]!!.at)
        assertEquals("a", next["k"]!!.dev)
    }

    // ---------- Two screens, no network ----------

    /** A screen reduced to what the diff needs: its items, what it last agreed, its clock and a name. */
    private class Screen(val name: String, var items: MutableMap<String, JsonElement> = HashMap()) {
        var base: Map<String, BaseRec> = emptyMap()
        val clock = SyncClock()
        val changed = HashMap<String, Long>()

        fun edit(key: String, v: JsonElement?, at: Long) {
            if (v == null) items.remove(key) else items[key] = v
            changed[key] = at
        }

        /** One sync run against [remote]; returns what the store should now hold. */
        fun sync(remote: SyncDoc, now: Long): SyncDoc {
            val local = SyncDoc(account = "", recs = SyncDiff.export(items, base, name, clock, now, { k, _ -> changed[k] }))
            clock.observe(SyncMerge.latest(remote))
            val merged = SyncMerge.merge(local, remote, now, "2026-11-12")
            items = merged.recs.filterValues { !it.gone }.mapValues { it.value.v!! }.toMutableMap()
            base = SyncDiff.rebase(merged.recs, items)
            changed.clear()
            return merged
        }
    }

    @Test
    fun anItemAddedOnOneScreenReachesTheOtherAndStaysAfterBothAgree() {
        val a = Screen("a")
        val b = Screen("b")
        var remote = SyncDoc()
        a.edit("fav:p:live:1", p("News"), 100)
        remote = a.sync(remote, 200)
        remote = b.sync(remote, 300)
        assertEquals(p("News"), b.items["fav:p:live:1"])
        remote = a.sync(remote, 400)
        assertEquals(1, remote.recs.size)
    }

    @Test
    fun aRemovalOnOneScreenIsNotUndoneByTheOtherSyncingLater() {
        val a = Screen("a")
        val b = Screen("b")
        var remote = SyncDoc()
        a.edit("hist:p:movie:1", p("half watched"), 100)
        remote = a.sync(remote, 200)
        remote = b.sync(remote, 300)
        b.edit("hist:p:movie:1", null, 400)
        remote = b.sync(remote, 500)
        remote = a.sync(remote, 600)
        assertTrue(a.items.isEmpty())
        remote = b.sync(remote, 700)
        assertTrue(remote.recs["hist:p:movie:1"]!!.gone)
        assertTrue(b.items.isEmpty())
    }

    @Test
    fun aScreenThatWasOfflineKeepsItsEditsAndTakesTheOthers() {
        val a = Screen("a")
        val b = Screen("b")
        var remote = SyncDoc()
        a.edit("k1", p("one"), 100)
        remote = a.sync(remote, 150)
        remote = b.sync(remote, 160)
        // b is offline while both change different things
        b.edit("k2", p("from b"), 300)
        a.edit("k3", p("from a"), 310)
        remote = a.sync(remote, 320)
        remote = b.sync(remote, 900)
        remote = a.sync(remote, 950)
        assertEquals(a.items, b.items)
        assertEquals(setOf("k1", "k2", "k3"), a.items.keys)
    }

    @Test
    fun theLaterEditOfTheSameItemWinsOnBothScreens() {
        val a = Screen("a")
        val b = Screen("b")
        var remote = SyncDoc()
        a.edit("set:audioLang", p("en"), 100)
        remote = a.sync(remote, 110)
        remote = b.sync(remote, 120)
        a.edit("set:audioLang", p("hi"), 500)
        b.edit("set:audioLang", p("bn"), 600)
        remote = a.sync(remote, 700)
        remote = b.sync(remote, 710)
        remote = a.sync(remote, 720)
        assertEquals(p("bn"), a.items["set:audioLang"])
        assertEquals(p("bn"), b.items["set:audioLang"])
    }

    @Test
    fun aScreenWithAWrongClockStillSupersedesWhatItHasSeen() {
        val a = Screen("a")
        val slow = Screen("slow")
        var remote = SyncDoc()
        a.edit("k", p("one"), 10_000)
        remote = a.sync(remote, 10_100)
        remote = slow.sync(remote, 10_200)
        // The slow screen's clock says 100 when it changes the item a minute later.
        slow.edit("k", p("two"), 100)
        remote = slow.sync(remote, 100)
        remote = a.sync(remote, 10_300)
        assertEquals(p("two"), a.items["k"])
    }

    @Test
    fun aRestoredOlderStateSpreadsAsChangesMadeNow() {
        val a = Screen("a")
        val b = Screen("b")
        var remote = SyncDoc()
        a.edit("fav:p:live:1", p("One"), 100)
        a.edit("fav:p:live:2", p("Two"), 110)
        remote = a.sync(remote, 200)
        remote = b.sync(remote, 210)
        // a restores a backup that only had favourite 1, plus one that did not exist
        a.items.remove("fav:p:live:2")
        a.items["fav:p:live:3"] = p("Three")
        remote = a.sync(remote, 5_000)
        remote = b.sync(remote, 5_100)
        assertEquals(setOf("fav:p:live:1", "fav:p:live:3"), b.items.keys)
    }

    // ---------- rebaseChecked ----------

    @Test
    fun rebaseCheckedAgreesOnlyWhatArrivedAsIntended() {
        val merged = mapOf(
            "a" to SyncRec(10, "x", p("A")),
            "b" to SyncRec(11, "x", p("B")),
            "c" to SyncRec(12, "x", null),
            "d" to SyncRec(13, "x", p("D")),
        )
        val intended = mapOf("a" to p("A"), "b" to p("B"), "d" to p("D"))
        // "b" was changed by the viewer while the sync applied; "d" is not known to this screen.
        val after = mapOf("a" to p("A"), "b" to p("viewer"))
        val old = mapOf("b" to BaseRec(5, "me", SyncDiff.hash(p("older"))))
        val out = SyncDiff.rebaseChecked(merged, after, intended, old)
        assertEquals(BaseRec(10, "x", SyncDiff.hash(p("A"))), out["a"])
        assertEquals(old["b"], out["b"])
        assertEquals(BaseRec(12, "x", null), out["c"])
        assertNull(out["d"])
    }

    @Test
    fun rebaseCheckedLeavesOutAnUnmatchedItemThatWasNeverAgreed() {
        val merged = mapOf("a" to SyncRec(10, "x", p("A")))
        val out = SyncDiff.rebaseChecked(merged, mapOf("a" to p("other")), mapOf("a" to p("A")), emptyMap())
        assertNull(out["a"])
    }

    @Test
    fun rebaseCheckedAcceptsAValueTheScreenStoresDifferentlyWhenItMeantTo() {
        // The merge decoded a record from a newer version and the store keeps only the fields it knows:
        // what the store holds is what was intended, so the item counts as agreed.
        val merged = mapOf("a" to SyncRec(10, "x", p("A+extra")))
        val out = SyncDiff.rebaseChecked(merged, mapOf("a" to p("A")), mapOf("a" to p("A")), emptyMap())
        assertEquals(BaseRec(10, "x", SyncDiff.hash(p("A"))), out["a"])
    }
}
