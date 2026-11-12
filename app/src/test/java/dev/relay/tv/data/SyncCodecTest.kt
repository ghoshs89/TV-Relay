package dev.relay.tv.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCodecTest {
    private val today = "2026-11-12"
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun entry(key: String, updated: Long, kind: String = "movie") = WatchEntry(
        key = key, kind = kind, title = key, streamId = key.substringAfter(':'), ext = "mp4",
        position = 60_000, duration = 600_000, updated = updated,
    )

    private val kids = Profile(id = "p2", name = "Kids", pin = "1234", kids = true, color = 2, dailyLimitMin = 60, bedtime = "20:00", wakeTime = "07:00")
    private val me = Profile(id = "p1", name = "Me", color = 0)

    private fun full() = SyncData(
        profiles = listOf(me, kids),
        favs = mapOf(
            "p1" to listOf(FavItem("live", 2, "B", at = 5), FavItem("movie", 1, "A", at = 9), FavItem("series", 3, "C", at = 7)),
        ),
        history = mapOf("p1" to listOf(entry("movie:2", 300), entry("ep:9", 200, "episode"), entry("movie:1", 100))),
        settings = Settings(tmdbKey = "tk", ratingsKey = "rk", audioLang = "hi", hiddenCats = setOf("live:1"), sleepMinutes = 45),
        tracks = mapOf(77 to TrackPref(audio = "hi", text = "en")),
        reminders = listOf(Reminder(5, "Ch5", "News", 1_000, 2_000)),
        liveSort = mapOf("cat:1" to 2),
        pinnedCats = listOf("live:9"),
        channels = ChannelCustom(renames = mapOf(4 to "Four"), groups = listOf(ChannelGroup("g1", "Sport", listOf(1, 2)))),
        parentPin = "salt:hash",
        account = Account("Home", listOf("http://h:80"), "user", "pass"),
        useOwn = mapOf("p2" to "$today|${10 * minute}"),
        useOthers = mapOf("p2" to mapOf("tv2" to "$today|${20 * minute}")),
        allow = mapOf("p2" to now + 30 * minute),
        screens = mapOf("me" to Screen("Living room", 5)),
    )

    private fun stamp(m: Map<String, JsonElement>, at: Long = 1, dev: String = "me") = m.mapValues { SyncRec(at, dev, it.value) }

    private fun records(d: SyncData) = SyncCodec.toRecords(d, "me", today, now)

    @Test
    fun everythingSurvivesTheTripThroughTheDocument() {
        val d = full()
        val back = SyncCodec.fromRecords(stamp(records(d)), SyncData(), "me", today)
        assertEquals(d.profiles, back.profiles)
        assertEquals(d.favs, back.favs)
        assertEquals(d.history, back.history)
        assertEquals(d.tracks, back.tracks)
        assertEquals(d.reminders, back.reminders)
        assertEquals(d.liveSort, back.liveSort)
        assertEquals(d.pinnedCats, back.pinnedCats)
        assertEquals(d.channels, back.channels)
        assertEquals(d.parentPin, back.parentPin)
        assertEquals(d.account, back.account)
        assertEquals(d.allow, back.allow)
        assertEquals(d.screens, back.screens)
        assertEquals("tk", back.settings.tmdbKey)
        assertEquals("rk", back.settings.ratingsKey)
        assertEquals("hi", back.settings.audioLang)
        assertEquals(setOf("live:1"), back.settings.hiddenCats)
    }

    @Test
    fun theScreensOwnSettingsDoNotTravel() {
        val back = SyncCodec.fromRecords(stamp(records(full())), SyncData(settings = Settings(sleepMinutes = 0, audioDelayMs = 200)), "me", today)
        assertEquals(0, back.settings.sleepMinutes)
        assertEquals(200, back.settings.audioDelayMs)
        assertFalse(records(full()).keys.any { it == "set:sleepMinutes" || it == "set:audioDelayMs" })
    }

    @Test
    fun emptyThingsAreLeftOutSoTheyCannotOverwriteAnotherScreen() {
        val keys = records(SyncData(profiles = listOf(me))).keys
        assertFalse("pincats" in keys)
        assertFalse("chcustom" in keys)
        assertFalse("parentpin" in keys)
        assertFalse("account" in keys)
        assertFalse(keys.any { it.startsWith("fav:") || it.startsWith("favorder:") || it.startsWith("hist:") })
        assertTrue("profile:p1" in keys)
    }

    @Test
    fun favouritesKeepTheirOrderAndNewOnesComeFirst() {
        val d = full()
        val recs = stamp(records(d)).toMutableMap()
        // Another screen added a series favourite after the order was last written.
        recs["fav:p1:series:8"] = SyncRec(2, "tv2", Jsons.json.encodeToJsonElement(FavItem("series", 8, "New", at = 50)))
        val back = SyncCodec.fromRecords(recs, SyncData(), "me", today)
        val ids = back.favs["p1"]!!.map { "${it.kind}:${it.id}" }
        assertEquals(listOf("series:8", "live:2", "movie:1", "series:3"), ids)
    }

    @Test
    fun historyComesBackNewestFirstAndCapped() {
        val many = (1..450).map { entry("movie:$it", it.toLong()) }
        val d = SyncData(profiles = listOf(me), history = mapOf("p1" to many))
        val back = SyncCodec.fromRecords(stamp(records(d)), SyncData(), "me", today)
        val h = back.history["p1"]!!
        assertEquals(400, h.size)
        assertEquals("movie:450", h.first().key)
        assertEquals("movie:51", h.last().key)
    }

    @Test
    fun aRemovalInTheDocumentRemovesTheThingHere() {
        val local = full()
        val recs = stamp(records(local)).toMutableMap()
        recs["fav:p1:live:2"] = SyncRec(9, "tv2", null)
        recs["hist:p1:movie:1"] = SyncRec(9, "tv2", null)
        recs["rem:5:1000"] = SyncRec(9, "tv2", null)
        recs["pincats"] = SyncRec(9, "tv2", null)
        recs["parentpin"] = SyncRec(9, "tv2", null)
        recs["profile:p2"] = SyncRec(9, "tv2", null)
        val back = SyncCodec.fromRecords(recs, local, "me", today)
        assertEquals(listOf("movie:1", "series:3"), back.favs["p1"]!!.map { "${it.kind}:${it.id}" })
        assertEquals(listOf("movie:2", "ep:9"), back.history["p1"]!!.map { it.key })
        assertTrue(back.reminders.isEmpty())
        assertTrue(back.pinnedCats.isEmpty())
        assertNull(back.parentPin)
        assertEquals(listOf("p1"), back.profiles.map { it.id })
    }

    @Test
    fun anotherScreenSigningOutNeverSignsThisOneOut() {
        val local = full()
        val recs = stamp(records(local)).toMutableMap()
        recs["account"] = SyncRec(99, "tv2", null)
        assertEquals(local.account, SyncCodec.fromRecords(recs, local, "me", today).account)
    }

    @Test
    fun aDocumentWithoutProfilesNeverEmptiesThisScreensProfiles() {
        val local = full()
        val back = SyncCodec.fromRecords(emptyMap(), local, "me", today)
        assertEquals(local.profiles, back.profiles)
        assertEquals(local.account, back.account)
        assertEquals(local.parentPin, back.parentPin)
    }

    @Test
    fun theOtherScreensKidsTimeIsReadAndThisScreensOwnIsNot() {
        val back = SyncCodec.fromRecords(stamp(records(full())), full().copy(useOthers = emptyMap()), "me", today)
        assertEquals(mapOf("p2" to mapOf("tv2" to "$today|${20 * minute}")), back.useOthers)
        assertEquals(full().useOwn, back.useOwn)
    }

    @Test
    fun kidsTimeFromAnotherDayIsNotExportedOrApplied() {
        val d = full().copy(useOwn = mapOf("p2" to "2026-11-11|${50 * minute}"), useOthers = mapOf("p2" to mapOf("tv2" to "2026-11-11|1000")))
        assertFalse(records(d).keys.any { it.startsWith("use:") })
    }

    @Test
    fun anExtraMinutesAllowanceTravelsOnlyWhileItIsRecent() {
        val d = full().copy(allow = mapOf("p2" to now + 30 * minute, "p1" to now - 3 * 86_400_000L))
        val keys = records(d).keys
        assertTrue("allow:p2" in keys)
        assertFalse("allow:p1" in keys)
    }

    @Test
    fun anItemThatCannotBeReadIsSkippedAndTheRestStillApply() {
        val recs = stamp(records(full())).toMutableMap()
        recs["fav:p1:movie:99"] = SyncRec(5, "tv2", JsonPrimitive("not an object"))
        recs["trk:abc"] = SyncRec(5, "tv2", JsonPrimitive("x"))
        val back = SyncCodec.fromRecords(recs, SyncData(), "me", today)
        assertEquals(full().favs, back.favs)
        assertEquals(full().tracks, back.tracks)
    }

    @Test
    fun recordsOfADeletedProfileAreNotApplied() {
        val recs = stamp(records(full())).toMutableMap()
        recs["fav:ghost:live:1"] = SyncRec(5, "tv2", Jsons.json.encodeToJsonElement(FavItem("live", 1, "X")))
        val back = SyncCodec.fromRecords(recs, SyncData(), "me", today)
        assertNull(back.favs["ghost"])
    }

    @Test
    fun profilesFollowTheDocumentsOrder() {
        val d = full().copy(profiles = listOf(kids, me))
        val back = SyncCodec.fromRecords(stamp(records(d)), SyncData(profiles = listOf(me, kids)), "me", today)
        assertEquals(listOf("p2", "p1"), back.profiles.map { it.id })
    }

    @Test
    fun theAccountTagIgnoresCaseAndServerButNotTheUser() {
        val a = Account("A", listOf("http://one"), "Alice", "x")
        assertEquals(SyncCodec.accountTag(a), SyncCodec.accountTag(a.copy(hosts = listOf("http://two"), username = "alice")))
        assertTrue(SyncCodec.accountTag(a) != SyncCodec.accountTag(a.copy(username = "bob")))
        assertEquals("", SyncCodec.accountTag(null))
        assertEquals(12, SyncCodec.accountTag(a).length)
    }
}
