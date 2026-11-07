package dev.relay.tv.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {

    private val dad = Profile("p1", "Dad", pin = "1234")
    private val kid = Profile("p2", "Kid", kids = true)

    private val full = BackupFile(
        appVersion = "1.0.20",
        created = 5L,
        profiles = listOf(dad, kid),
        lockedProfiles = listOf("p1"),
        favourites = mapOf("p1" to listOf(FavItem("live", 7, "News"))),
        history = mapOf("p2" to listOf(WatchEntry("movie:1", "movie", "Film", streamId = "1", ext = "mp4", position = 60_000, duration = 600_000))),
        settings = Settings(tmdbKey = "tmdb-secret", ratingsKey = "mdb-secret", logUntil = 99L, audioLang = "fr", timeshiftMinutes = 60),
        channels = ChannelCustom(renames = mapOf(7 to "My News"), groups = listOf(ChannelGroup("g", "Sport", listOf(7, 8)))),
        pinnedCategories = listOf("1"),
        liveSort = mapOf("1" to 2),
        seriesTracks = mapOf(42 to TrackPref(audio = "en", text = "fr")),
        reminders = listOf(Reminder(7, "News", "Late show", 100L, 200L)),
    )

    @Test
    fun exportThenImportKeepsEverythingButSecrets() {
        val text = Backups.encode(full)
        val back = Backups.decode(text)
        val expected = full.copy(
            profiles = listOf(dad.copy(pin = null), kid),
            settings = full.settings.copy(tmdbKey = "", ratingsKey = "", logUntil = 0L),
        )
        assertEquals(expected, back)
    }

    @Test
    fun noPinOrKeyIsWritten() {
        val text = Backups.encode(full)
        assertFalse(text.contains("1234"))
        assertFalse(text.contains("tmdb-secret"))
        assertFalse(text.contains("mdb-secret"))
        assertTrue(text.contains("\"schema\""))
    }

    @Test
    fun malformedFilesAreRejected() {
        expectReject("not json at all")
        expectReject("""{"app":"other","schema":2}""")
        expectReject("""{"app":"relay","schema":2,"profiles":"oops"}""")
        expectReject("""{"app":"relay","schema":2,"profiles":[]}""")
        expectReject("""{"app":"relay","schema":2,"profiles":[{"id":"a","name":"A"},{"id":"a","name":"B"}]}""")
        expectReject("""{"app":"relay"}""")
    }

    @Test
    fun aNewerFormatIsRejectedWithAClearMessage() {
        try {
            Backups.decode("""{"app":"relay","schema":3,"profiles":[{"id":"a","name":"A"}]}""")
            fail()
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun formatOneBackupsImportWithoutPinsOrKeys() {
        val j = Jsons.json
        val data = buildJsonObject {
            put("profiles", j.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), listOf(dad, kid)))
            put("settings", j.encodeToString(Settings.serializer(), Settings(tmdbKey = "tmdb-secret", audioLang = "hi")))
            put("fav_p1", j.encodeToString(kotlinx.serialization.builtins.ListSerializer(FavItem.serializer()), listOf(FavItem("movie", 3, "Film"))))
            put("trk_42", j.encodeToString(TrackPref.serializer(), TrackPref(audio = "en")))
            put("lastcat_p1", "news")
            put("parent_pin", "abc:def")
        }
        val v1 = buildJsonObject {
            put("app", "relay")
            put("version", 1)
            put("data", data)
        }.toString()
        val b = Backups.decode(v1)
        assertEquals(listOf("Dad", "Kid"), b.profiles.map { it.name })
        assertTrue(b.profiles.all { it.pin == null })
        assertEquals(listOf("p1"), b.lockedProfiles)
        assertEquals("", b.settings.tmdbKey)
        assertEquals("hi", b.settings.audioLang)
        assertEquals(3, b.favourites["p1"]!!.single().id)
        assertEquals("en", b.seriesTracks[42]!!.audio)
    }

    @Test
    fun restoreSummaryNamesProfilesThatNeedAPin() {
        val r = RestoreResult(2, 5, 10, 1, listOf("Dad"))
        assertTrue(r.summary().contains("Set a PIN again for: Dad"))
        assertTrue(r.summary().startsWith("Restored 2 profiles"))
    }

    private fun expectReject(text: String) {
        try {
            Backups.decode(text)
            fail("accepted: $text")
        } catch (_: BackupException) {
        }
    }
}
