package dev.relay.tv.sync

import dev.relay.tv.data.BaseRec
import dev.relay.tv.data.SealException
import dev.relay.tv.data.SyncDoc
import dev.relay.tv.data.SyncRec
import dev.relay.tv.data.SyncSeal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** A store in memory that behaves like GitHub's: a write names the version it read, and loses if that is stale. */
private class FakeTransport : SyncTransport {
    var data: ByteArray? = null
    var ver = 0
    var writes = 0
    var reads = 0
    var failRead: TransportException? = null
    var failWrite: TransportException? = null

    /** Runs once, just before the next write is checked: another screen getting in first. */
    var beforeWrite: (() -> Unit)? = null

    override fun read(): Remote? {
        reads++
        failRead?.let { throw it }
        return data?.let { Remote(it.copyOf(), "v$ver") }
    }

    override fun write(bytes: ByteArray, ifVersion: String?): WriteResult {
        beforeWrite?.let { beforeWrite = null; it() }
        failWrite?.let { throw it }
        val current = if (data == null) null else "v$ver"
        if (current != ifVersion) return WriteResult.Changed
        data = bytes.copyOf()
        ver++
        writes++
        return WriteResult.Saved("v$ver")
    }
}

private class MemState : SyncStateStore {
    var state = SyncState()
    override fun load() = state
    override fun save(state: SyncState) { this.state = state }
}

/** A screen whose data is a map of items. Times of changes are kept the way the real store keeps them. */
private class FakeLocal(override val deviceId: String, val name: String, var account: String = "acct") : SyncLocal {
    val data = LinkedHashMap<String, JsonElement>()
    val changed = HashMap<String, Long>()
    val backups = ArrayList<String>()
    private var screenSeen = 0L

    /** What the viewer does while a sync is applying: these items are changed after the sync read the state. */
    var raceOnApply: Map<String, JsonElement> = emptyMap()

    fun set(key: String, value: String, at: Long) {
        data[key] = JsonPrimitive(value)
        changed[key] = at
    }

    fun remove(key: String, at: Long) {
        data.remove(key)
        changed[key] = at
    }

    fun value(key: String): String? = (data[key] as? JsonPrimitive)?.content

    override fun accountTag() = account

    override fun items(screenSeen: Long): Map<String, JsonElement> {
        this.screenSeen = screenSeen
        return LinkedHashMap(data).also {
            it["screen:$deviceId"] = JsonObject(mapOf("name" to JsonPrimitive(name), "seen" to JsonPrimitive(screenSeen)))
        }
    }

    override fun changedAt(key: String, v: JsonElement?): Long? = changed[key]

    override fun isDefault(key: String, v: JsonElement) = false

    override fun apply(merged: Map<String, SyncRec>): Applied {
        val before = HashMap(data)
        data.clear()
        for ((k, r) in merged) if (!r.gone && !k.startsWith("screen:")) data[k] = r.v!!
        val intended = items(screenSeen)
        for ((k, v) in raceOnApply) data[k] = v
        raceOnApply = emptyMap()
        val n = data.keys.count { data[it] != before[it] } + before.keys.count { it !in data }
        return Applied(items(screenSeen), intended, dev.relay.tv.data.SyncApplied(n, false, false))
    }

    override fun backup(reason: String) {
        backups += reason
    }
}

class SyncEngineTest {
    private val key = SyncSeal.newKey()
    private var clockMs = 1_800_000_000_000L
    private fun tick(): Long { clockMs += 1000; return clockMs }

    private val transport = FakeTransport()

    private inner class Screen(id: String, name: String = id, account: String = "acct") {
        val local = FakeLocal(id, name, account)
        val state = MemState()
        var theKey = key
        fun engine(t: SyncTransport = transport) = SyncEngine(local, t, theKey, state, now = { tick() }, today = { "2027-01-15" })
        fun sync() = engine().run()
    }

    private fun remoteDoc(): SyncDoc = SyncDoc.parse(String(SyncSeal.open(transport.data!!, key)))

    // ---------- the basics ----------

    @Test fun firstScreenPushesEverything() {
        val a = Screen("a", "Living room")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.local.set("set:audioLang", "en", tick())
        val r = a.sync()
        assertEquals(SyncResult.Pushed, r.result)
        assertTrue(r.pushed, r.pushed.contains("1 favourite"))
        assertEquals(1, transport.writes)
        val doc = remoteDoc()
        assertEquals("acct", doc.account)
        assertEquals("Heat", (doc.recs["fav:p:movie:1"]!!.v as JsonPrimitive).content)
        assertNotNull(doc.recs["screen:a"])
        assertTrue(a.state.state.synced)
    }

    @Test fun nothingNewMeansNoWrite() {
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync()
        val writes = transport.writes
        val r = a.sync()
        assertEquals(SyncResult.UpToDate, r.result)
        assertEquals(writes, transport.writes)
        assertEquals(SyncResult.UpToDate, a.sync().result)
        assertEquals(writes, transport.writes)
    }

    @Test fun secondScreenPullsWhatTheFirstPushed() {
        val a = Screen("a", "Living room")
        val b = Screen("b", "Bedroom")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.local.set("hist:p:k", "45%", tick())
        a.sync()
        val r = b.sync()
        assertEquals(SyncResult.Pulled, r.result)
        assertTrue(r.pulled, r.pulled.contains("from Living room"))
        assertEquals("Heat", b.local.value("fav:p:movie:1"))
        assertEquals("45%", b.local.value("hist:p:k"))
    }

    @Test fun twoScreensTakingTurnsConverge() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync()
        b.sync()
        b.local.set("fav:p:movie:2", "Alien", tick())
        b.sync()
        a.sync()
        assertEquals("Alien", a.local.value("fav:p:movie:2"))
        assertEquals("Heat", b.local.value("fav:p:movie:1"))
        assertEquals(a.local.data, b.local.data)
        val writes = transport.writes
        assertEquals(SyncResult.UpToDate, a.sync().result)
        assertEquals(SyncResult.UpToDate, b.sync().result)
        assertEquals(writes, transport.writes)
    }

    @Test fun deletingAnItemAndEditingAnotherConvergeAndTheItemCanBeAddedAgain() {
        val tv = Screen("tv")
        val phone = Screen("phone")
        for (prefix in listOf("fav:p:movie", "hist:p:movie")) {
            val removed = "$prefix:1"
            val edited = "$prefix:2"
            tv.local.set(removed, "A", tick())
            tv.local.set(edited, "B", tick())
            tv.sync()
            phone.sync()
            tv.local.remove(removed, tick())
            tv.sync()
            phone.local.raceOnApply = mapOf(edited to JsonPrimitive("B updated"))
            phone.sync()
            phone.sync()
            tv.sync()

            for (screen in listOf(tv, phone)) {
                assertNull(screen.local.value(removed))
                assertEquals("B updated", screen.local.value(edited))
            }
            phone.local.set(removed, "A added again", tick())
            phone.sync()
            tv.sync()
            assertEquals("A added again", tv.local.value(removed))
        }
    }

    @Test fun laterChangeWinsOnBothScreens() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("set:audioLang", "en", tick())
        a.sync(); b.sync()
        a.local.set("set:audioLang", "fr", tick())
        b.local.set("set:audioLang", "de", tick())
        a.sync(); b.sync(); a.sync()
        assertEquals("de", a.local.value("set:audioLang"))
        assertEquals("de", b.local.value("set:audioLang"))
    }

    // ---------- removals ----------

    @Test fun aRemovalSpreadsAndDoesNotComeBack() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("hist:p:k", "45%", tick())
        a.local.set("hist:p:j", "10%", tick())
        a.sync(); b.sync()
        a.local.remove("hist:p:k", tick())
        val r = a.sync()
        assertEquals(SyncResult.Pushed, r.result)
        b.sync()
        assertNull(b.local.value("hist:p:k"))
        assertEquals("10%", b.local.value("hist:p:j"))
        // Neither screen brings it back.
        b.sync(); a.sync(); b.sync()
        assertNull(a.local.value("hist:p:k"))
        assertNull(b.local.value("hist:p:k"))
        assertTrue(remoteDoc().recs["hist:p:k"]!!.gone)
    }

    @Test fun addingBackAfterARemovalWins() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync(); b.sync()
        a.local.remove("fav:p:movie:1", tick()); a.sync(); b.sync()
        assertNull(b.local.value("fav:p:movie:1"))
        b.local.set("fav:p:movie:1", "Heat again", tick()); b.sync(); a.sync()
        assertEquals("Heat again", a.local.value("fav:p:movie:1"))
        assertEquals("Heat again", b.local.value("fav:p:movie:1"))
    }

    @Test fun anOlderEditOnAnotherScreenDoesNotBeatARemoval() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync(); b.sync()
        b.local.set("fav:p:movie:1", "edited early", tick())
        a.local.remove("fav:p:movie:1", tick())
        a.sync(); b.sync(); a.sync()
        assertNull(a.local.value("fav:p:movie:1"))
        assertNull(b.local.value("fav:p:movie:1"))
    }

    // ---------- collisions and trouble ----------

    @Test fun aCollisionIsRetriedAndBothChangesSurvive() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync(); b.sync()
        a.local.set("fav:p:movie:2", "Alien", tick())
        b.local.set("fav:p:movie:3", "Brazil", tick())
        // B gets in just before A's write is checked.
        transport.beforeWrite = { b.sync() }
        val r = a.sync()
        assertTrue(r.result.toString(), r.ok)
        b.sync()
        assertEquals("Brazil", a.local.value("fav:p:movie:3"))
        assertEquals("Alien", b.local.value("fav:p:movie:2"))
        assertEquals(a.local.data, b.local.data)
        assertEquals(setOf("fav:p:movie:1", "fav:p:movie:2", "fav:p:movie:3"), remoteDoc().recs.filter { !it.value.gone && it.key.startsWith("fav:") }.keys)
    }

    @Test fun givesUpAfterThreeCollisions() {
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick())
        var n = 0
        val noisy = object : SyncTransport {
            override fun read() = transport.read()
            override fun write(bytes: ByteArray, ifVersion: String?): WriteResult { n++; return WriteResult.Changed }
        }
        val r = a.engine(noisy).run()
        assertEquals(SyncResult.Problem, r.result)
        assertEquals(SyncEngine.MAX_ATTEMPTS, n)
    }

    @Test fun offlineChangesNothing() {
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick())
        transport.failRead = TransportException(Problem.Offline, "no network")
        val before = LinkedHashMap(a.local.data)
        val r = a.sync()
        assertEquals(SyncResult.Offline, r.result)
        assertEquals(before, a.local.data)
        assertEquals(SyncState(), a.state.state)
        assertNull(transport.data)
    }

    @Test fun offlineDuringTheWriteChangesNothingHere() {
        val a = Screen("a")
        val b = Screen("b")
        b.local.set("fav:p:movie:9", "Other", tick()); b.sync()
        a.local.set("fav:p:movie:1", "Heat", tick())
        transport.failWrite = TransportException(Problem.Offline, "lost the network")
        val r = a.sync()
        assertEquals(SyncResult.Offline, r.result)
        assertNull(a.local.value("fav:p:movie:9"))
        assertFalse(a.state.state.synced)
        // Back online: everything still arrives, nothing was lost.
        transport.failWrite = null
        assertTrue(a.sync().ok)
        b.sync()
        assertEquals("Heat", b.local.value("fav:p:movie:1"))
        assertEquals("Other", a.local.value("fav:p:movie:9"))
    }

    @Test fun refusedTokenIsReported() {
        val a = Screen("a")
        transport.failRead = TransportException(Problem.Refused, "refused")
        assertEquals(SyncResult.Refused, a.sync().result)
        transport.failRead = TransportException(Problem.NotFound, "gone")
        assertEquals(SyncResult.NotFound, a.sync().result)
        transport.failRead = TransportException(Problem.Other, "odd")
        assertEquals(SyncResult.Problem, a.sync().result)
    }

    @Test fun wrongKeyChangesNothingAnywhere() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync()
        b.theKey = SyncSeal.newKey()
        b.local.set("fav:p:movie:2", "Alien", tick())
        val file = transport.data!!.copyOf()
        val before = LinkedHashMap(b.local.data)
        val r = b.sync()
        assertEquals(SyncResult.WrongKey, r.result)
        assertEquals(before, b.local.data)
        assertEquals(file.toList(), transport.data!!.toList())
        assertEquals(1, transport.writes)
    }

    @Test fun anotherProviderAccountIsRefused() {
        val a = Screen("a", account = "tagA")
        val b = Screen("b", account = "tagB")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync()
        b.local.set("fav:p:movie:2", "Alien", tick())
        val r = b.sync()
        assertEquals(SyncResult.WrongAccount, r.result)
        assertNull(b.local.value("fav:p:movie:1"))
        assertEquals(1, transport.writes)
    }

    @Test fun aScreenWithoutALoginTakesTheHouseholdsAccount() {
        val a = Screen("a", account = "tagA")
        val b = Screen("b", account = "")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync()
        assertTrue(b.sync().ok)
        assertEquals("Heat", b.local.value("fav:p:movie:1"))
    }

    @Test fun aNewerFileIsReadOnlyForAnOlderApp() {
        val newer = SyncDoc(format = 2, account = "acct", recs = mapOf("x" to SyncRec(5, "z", JsonPrimitive("1"))))
        transport.data = SyncSeal.seal(newer.encode().toByteArray(), key)
        transport.ver = 1
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick())
        val r = a.sync()
        assertEquals(SyncResult.NeedsUpdate, r.result)
        assertEquals(0, transport.writes)
    }

    @Test fun aDamagedFileIsNotOverwritten() {
        transport.data = ByteArray(80) { 7 }
        transport.ver = 1
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick())
        val r = a.sync()
        assertEquals(SyncResult.Problem, r.result)
        assertEquals(0, transport.writes)
        assertEquals("Heat", a.local.value("fav:p:movie:1"))
    }

    @Test fun aFileThatIsTooLargeIsNotWritten() {
        val a = Screen("a")
        val rnd = Random(3)
        for (i in 0 until 1200) {
            val junk = ByteArray(700).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            a.local.set("hist:p$i:k", junk, tick())
        }
        val r = a.sync()
        assertEquals(SyncResult.Problem, r.result)
        assertTrue(r.message, r.message.contains("too large"))
        assertEquals(0, transport.writes)
    }

    @Test fun aRecreatedRepoIsFilledAgain() {
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync()
        transport.data = null
        val r = a.sync()
        assertEquals(SyncResult.Pushed, r.result)
        assertEquals("Heat", (remoteDoc().recs["fav:p:movie:1"]!!.v as JsonPrimitive).content)
    }

    // ---------- safety copy ----------

    @Test fun aCopyIsKeptBeforeTheFirstSyncOnly() {
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync()
        assertEquals(1, a.local.backups.size)
        a.sync()
        a.local.set("fav:p:movie:2", "Alien", tick())
        a.sync()
        assertEquals(1, a.local.backups.size)
    }

    @Test fun aCopyIsKeptBeforeChangesFromAnotherScreenAreApplied() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync(); b.sync()
        assertEquals(1, b.local.backups.size)
        // Within six hours no new copy; after it, one.
        a.local.set("fav:p:movie:2", "Alien", tick()); a.sync(); b.sync()
        assertEquals(1, b.local.backups.size)
        clockMs += SyncEngine.BACKUP_EVERY_MS + 5_000
        a.local.set("fav:p:movie:3", "Brazil", tick()); a.sync(); b.sync()
        assertEquals(2, b.local.backups.size)
    }

    // ---------- the viewer changes something while a sync is applying ----------

    @Test fun achangeMadeWhileApplyingIsKeptAndSyncedNextTime() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("set:audioLang", "en", tick()); a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync(); b.sync()
        a.local.set("set:audioLang", "fr", tick()); a.sync()
        // B applies A's change, and in that moment the viewer on B picks German.
        b.local.raceOnApply = mapOf("set:audioLang" to JsonPrimitive("de"))
        b.local.changed["set:audioLang"] = tick() + 500
        b.sync()
        assertEquals("de", b.local.value("set:audioLang"))
        b.sync(); a.sync(); b.sync()
        assertEquals("de", a.local.value("set:audioLang"))
        assertEquals("de", b.local.value("set:audioLang"))
        assertEquals("de", (remoteDoc().recs["set:audioLang"]!!.v as JsonPrimitive).content)
    }

    // ---------- unknown fields from a newer version travel through ----------

    @Test fun unknownFieldsAreCarriedThrough() {
        val a = Screen("a")
        val b = Screen("b")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync()
        val doc = remoteDoc()
        val withExtra = doc.copy(
            extra = mapOf("future" to JsonPrimitive("kept")),
            recs = doc.recs + ("fav:p:movie:1" to doc.recs["fav:p:movie:1"]!!.copy(extra = mapOf("note" to JsonPrimitive("n")))),
        )
        transport.data = SyncSeal.seal(withExtra.encode().toByteArray(), key)
        transport.ver++
        b.local.set("fav:p:movie:2", "Alien", tick())
        b.sync()
        val after = remoteDoc()
        assertEquals(JsonPrimitive("kept"), after.extra["future"])
        assertEquals(JsonPrimitive("n"), after.recs["fav:p:movie:1"]!!.extra["note"])
    }

    // ---------- the list of screens ----------

    @Test fun screensAreListedWithTheirNames() {
        val a = Screen("a", "Living room")
        val b = Screen("b", "Bedroom")
        a.sync(); b.sync()
        val r = a.sync()
        assertEquals(setOf("Living room", "Bedroom"), r.screens.map { it.name }.toSet())
        assertEquals(setOf("a", "b"), r.screens.map { it.id }.toSet())
    }

    @Test fun forgettingAScreenTakesItOffTheListUntilItSyncsAgain() {
        val a = Screen("a", "Living room")
        val b = Screen("b", "Bedroom")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync(); b.sync(); a.sync()
        assertTrue(a.engine().forget("b"))
        assertEquals(listOf("a"), a.sync().screens.map { it.id })
        // Nothing but the entry went.
        assertEquals("Heat", (remoteDoc().recs["fav:p:movie:1"]!!.v as JsonPrimitive).content)
        // The screen that still holds the key puts itself back when it next syncs.
        assertTrue(b.sync().ok)
        assertTrue(b.sync().ok)
        assertEquals(setOf("a", "b"), a.sync().screens.map { it.id }.toSet())
    }

    @Test fun forgettingWithNothingThereIsFine() {
        val a = Screen("a")
        assertTrue(a.engine().forget("a"))
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync()
        assertTrue(a.engine().forget("zzz"))
    }

    // ---------- a new household key ----------

    @Test fun aNewKeyLocksOutScreensThatKeepTheOldOne() {
        val a = Screen("a")
        val lost = Screen("lost")
        a.local.set("fav:p:movie:1", "Heat", tick())
        a.sync(); lost.sync()
        val newKey = SyncSeal.newKey()
        assertTrue(a.engine().rekey(newKey))
        // The lost screen can no longer open the file or add to it.
        lost.local.set("fav:p:movie:2", "Stolen", tick())
        val writes = transport.writes
        assertEquals(SyncResult.WrongKey, lost.sync().result)
        assertEquals(writes, transport.writes)
        // The screen with the new key carries on, with all its data.
        a.theKey = newKey
        assertTrue(a.sync().ok)
        assertEquals("Heat", (SyncDoc.parse(String(SyncSeal.open(transport.data!!, newKey))).recs["fav:p:movie:1"]!!.v as JsonPrimitive).content)
        assertNull(a.local.value("fav:p:movie:2"))
    }

    @Test fun rekeyingWithTheWrongKeyChangesNothing() {
        val a = Screen("a")
        a.local.set("fav:p:movie:1", "Heat", tick()); a.sync()
        val before = transport.data!!.copyOf()
        a.theKey = SyncSeal.newKey()
        assertFalse(a.engine().rekey(SyncSeal.newKey()))
        assertEquals(before.toList(), transport.data!!.toList())
        // Nothing there to re-seal.
        transport.data = null
        assertFalse(Screen("z").engine().rekey(SyncSeal.newKey()))
    }

    // ---------- many screens, many changes ----------

    private fun randomRun(seed: Long, screens: Int, steps: Int) {
        val rnd = Random(seed)
        val all = (0 until screens).map { Screen("s$it") }
        val keys = (0 until 12).map { if (it % 3 == 0) "fav:p:movie:$it" else if (it % 3 == 1) "hist:p:k$it" else "set:s$it" }
        repeat(steps) {
            val s = all[rnd.nextInt(screens)]
            when (rnd.nextInt(4)) {
                0 -> s.local.set(keys[rnd.nextInt(keys.size)], "v${rnd.nextInt(1000)}", tick())
                1 -> keys[rnd.nextInt(keys.size)].let { k -> if (s.local.data.containsKey(k)) s.local.remove(k, tick()) }
                else -> s.sync().also { assertTrue("seed $seed: ${it.result} ${it.message}", it.ok) }
            }
        }
        repeat(3) { all.forEach { s -> assertTrue(s.sync().ok) } }
        val first = all[0].local.data
        for (s in all) assertEquals("seed $seed", first, s.local.data)
        val live = remoteDoc().recs.filter { !it.value.gone && !it.key.startsWith("screen:") }.mapValues { (it.value.v as JsonPrimitive).content }
        assertEquals("seed $seed", first.mapValues { (it.value as JsonPrimitive).content }, live)
        val writes = transport.writes
        all.forEach { assertEquals(SyncResult.UpToDate, it.sync().result) }
        assertEquals("seed $seed settles", writes, transport.writes)
    }

    @Test fun threeScreensAlwaysConverge() {
        for (seed in 1L..40L) {
            // A fresh store and clock for each run.
            transport.data = null; transport.ver = 0; transport.writes = 0
            randomRun(seed, 3, 120)
        }
    }

    @Test fun twoScreensAlwaysConverge() {
        for (seed in 100L..140L) {
            transport.data = null; transport.ver = 0; transport.writes = 0
            randomRun(seed, 2, 80)
        }
    }
}
