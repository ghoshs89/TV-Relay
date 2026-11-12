package dev.relay.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTextTest {
    private val v3 = "0123456789abcdef0123456789abcdef"
    private val v4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhYmMifQ.c2lnbmF0dXJlLXBhcnQ_-x"

    // ---------- clean ----------

    @Test
    fun aPlainKeyIsUnchanged() {
        assertEquals(v3, KeyText.clean(v3))
        assertEquals(v4, KeyText.clean(v4))
    }

    @Test
    fun spacesLineBreaksAndQuotesAreRemoved() {
        assertEquals(v3, KeyText.clean("  $v3  "))
        assertEquals(v3, KeyText.clean("\"$v3\""))
        assertEquals(v3, KeyText.clean("“$v3”"))
        assertEquals(v3, KeyText.clean("'$v3'\n"))
        assertEquals(v3, KeyText.clean("<$v3>"))
    }

    @Test
    fun aKeyWrappedOverSeveralLinesIsJoined() {
        assertEquals(v3, KeyText.clean(v3.substring(0, 16) + "\n" + v3.substring(16)))
        assertEquals(v4, KeyText.clean(v4.substring(0, 20) + " \r\n" + v4.substring(20)))
    }

    @Test
    fun aLeadingNameIsStripped() {
        assertEquals(v3, KeyText.clean("api_key=$v3"))
        assertEquals(v3, KeyText.clean("apikey=$v3"))
        assertEquals(v3, KeyText.clean("API_KEY = \"$v3\""))
        assertEquals(v3, KeyText.clean("TMDB_API_KEY=$v3"))
        assertEquals(v3, KeyText.clean("api_key: $v3"))
    }

    @Test
    fun aPastedWebAddressYieldsTheKey() {
        assertEquals(v3, KeyText.clean("https://api.themoviedb.org/3/movie/550?api_key=$v3"))
        assertEquals(v3, KeyText.clean("https://api.themoviedb.org/3/movie/550?api_key=$v3&language=en-US"))
        assertEquals("abc123", KeyText.clean("https://api.mdblist.com/tmdb/movie/550?apikey=abc123"))
    }

    @Test
    fun aLabelAroundTheKeyIsDropped() {
        assertEquals(v3, KeyText.clean("API Key (v3 auth) $v3"))
        assertEquals(v4, KeyText.clean("Bearer $v4"))
        assertEquals(v4, KeyText.clean("bearer\n$v4"))
    }

    @Test
    fun emptyAndBlankInputGiveNothing() {
        assertEquals("", KeyText.clean(""))
        assertEquals("", KeyText.clean("   \n\t "))
        assertEquals("", KeyText.clean("\"\""))
    }

    @Test
    fun rubbishComesBackAsIsWithoutCrashing() {
        assertEquals("hello", KeyText.clean("hello"))
        assertEquals("notakey", KeyText.clean("not a key"))
        assertEquals(KeyKind.Unknown, KeyText.kind("hello"))
    }

    // ---------- kind ----------

    @Test
    fun tellsTheTwoTmdbCredentialsApart() {
        assertEquals(KeyKind.V3, KeyText.kind(v3))
        assertEquals(KeyKind.V3, KeyText.kind(v3.uppercase()))
        assertEquals(KeyKind.V3, KeyText.kind("  \"$v3\" "))
        assertEquals(KeyKind.V4, KeyText.kind(v4))
        assertEquals(KeyKind.V4, KeyText.kind("Bearer $v4"))
    }

    @Test
    fun wrongLengthsAndShapesAreUnknown() {
        assertEquals(KeyKind.Unknown, KeyText.kind(v3.dropLast(1)))
        assertEquals(KeyKind.Unknown, KeyText.kind(v3 + "0"))
        assertEquals(KeyKind.Unknown, KeyText.kind("g" + v3.drop(1)))
        assertEquals(KeyKind.Unknown, KeyText.kind("eyJabc.def"))
        assertEquals(KeyKind.Unknown, KeyText.kind(""))
    }

    // ---------- checks ----------

    @Test
    fun tmdbAnswersMapToThreeOutcomes() {
        assertEquals(KeyCheck.Ok, KeyText.tmdbCheck(200))
        assertEquals(KeyCheck.Rejected, KeyText.tmdbCheck(401))
        assertEquals(KeyCheck.Rejected, KeyText.tmdbCheck(403))
        assertEquals(KeyCheck.Unreachable, KeyText.tmdbCheck(500))
        assertEquals(KeyCheck.Unreachable, KeyText.tmdbCheck(429))
        assertEquals(KeyCheck.Unreachable, KeyText.tmdbCheck(null))
    }

    @Test
    fun mdblistRepliesMapToThreeOutcomes() {
        assertEquals(KeyCheck.Ok, KeyText.ratingsCheck(MdbList.Reply.Found(80, 90)))
        assertEquals(KeyCheck.Ok, KeyText.ratingsCheck(MdbList.Reply.LimitReached))
        assertEquals(KeyCheck.Rejected, KeyText.ratingsCheck(MdbList.Reply.Failed("Invalid API key")))
        assertEquals(KeyCheck.Rejected, KeyText.ratingsCheck(MdbList.Reply.Failed("HTTP 403")))
        assertEquals(KeyCheck.Rejected, KeyText.ratingsCheck(MdbList.Reply.Failed("HTTP 401")))
        assertEquals(KeyCheck.Unreachable, KeyText.ratingsCheck(MdbList.Reply.Failed("HTTP 500")))
        assertEquals(KeyCheck.Unreachable, KeyText.ratingsCheck(MdbList.Reply.Failed("Unreadable reply")))
        assertEquals(KeyCheck.Unreachable, KeyText.ratingsCheck(MdbList.Reply.NotFound))
        assertEquals(KeyCheck.Unreachable, KeyText.ratingsCheck(null))
    }

    @Test
    fun messagesAreInPlainWords() {
        assertEquals("TMDB key saved", KeyText.message("TMDB", KeyCheck.Ok))
        assertEquals(
            "MDBList key was not accepted (check that you copied all of it)",
            KeyText.message("MDBList", KeyCheck.Rejected),
        )
        assertEquals("Could not reach TMDB, try again", KeyText.message("TMDB", KeyCheck.Unreachable))
        assertEquals("TMDB key removed", KeyText.removedMessage("TMDB"))
    }

    // ---------- intake ----------

    private data class Saved(var calls: Int = 0, var tmdb: String? = null, var ratings: String? = null)

    private fun run(
        form: KeysForm,
        tmdb: KeyCheck = KeyCheck.Ok,
        ratings: KeyCheck = KeyCheck.Ok,
        asked: MutableList<String> = mutableListOf(),
    ): Pair<KeysResult, Saved> {
        val saved = Saved()
        val result = runBlocking {
            KeysIntake.process(
                form,
                checkTmdb = { asked += "tmdb:$it"; tmdb },
                checkRatings = { asked += "mdb:$it"; ratings },
                apply = { t, r -> saved.calls++; saved.tmdb = t; saved.ratings = r },
            )
        }
        return result to saved
    }

    @Test
    fun aGoodKeyIsCheckedCleanedAndSavedOnce() {
        val asked = mutableListOf<String>()
        val (r, saved) = run(KeysForm(tmdb = " \"$v3\" ", ratings = "abc123"), asked = asked)
        assertEquals(listOf("tmdb:$v3", "mdb:abc123"), asked)
        assertEquals(1, saved.calls)
        assertEquals(v3, saved.tmdb)
        assertEquals("abc123", saved.ratings)
        assertEquals(listOf("TMDB key saved", "MDBList key saved"), r.lines)
        assertTrue(r.changed)
    }

    @Test
    fun aRejectedKeyIsNotSavedAndTheOtherStillIs() {
        val (r, saved) = run(KeysForm(tmdb = "bad", ratings = "good"), tmdb = KeyCheck.Rejected)
        assertEquals(1, saved.calls)
        assertNull(saved.tmdb)
        assertEquals("good", saved.ratings)
        assertEquals("TMDB key was not accepted (check that you copied all of it)", r.lines[0])
        assertEquals("MDBList key saved", r.lines[1])
        assertTrue(r.changed)
    }

    @Test
    fun anUnreachableServiceSavesNothing() {
        val (r, saved) = run(KeysForm(tmdb = v3), tmdb = KeyCheck.Unreachable)
        assertEquals(0, saved.calls)
        assertEquals(listOf("Could not reach TMDB, try again"), r.lines)
        assertFalse(r.changed)
    }

    @Test
    fun emptyBoxesLeaveEverythingAlone() {
        val asked = mutableListOf<String>()
        val (r, saved) = run(KeysForm(tmdb = "  ", ratings = ""), asked = asked)
        assertTrue(asked.isEmpty())
        assertEquals(0, saved.calls)
        assertFalse(r.changed)
        assertEquals(listOf("Nothing to save: both boxes were empty"), r.lines)
    }

    @Test
    fun theRemoveBoxClearsAKeyWithoutAskingTheService() {
        val asked = mutableListOf<String>()
        val (r, saved) = run(KeysForm(tmdb = v3, removeTmdb = true), asked = asked)
        assertTrue(asked.isEmpty())
        assertEquals("", saved.tmdb)
        assertNull(saved.ratings)
        assertEquals(listOf("TMDB key removed"), r.lines)
        assertTrue(r.changed)
    }

    @Test
    fun resultsNeverContainTheKeys() {
        val (r, _) = run(KeysForm(tmdb = v3, ratings = "supersecretvalue"), tmdb = KeyCheck.Rejected, ratings = KeyCheck.Ok)
        for (line in r.lines) {
            assertFalse(line.contains(v3))
            assertFalse(line.contains("supersecretvalue"))
        }
    }

    // ---------- logs ----------

    @Test
    fun logLinesNeverShowEitherKindOfTmdbCredential() {
        AppLog.secrets = { listOf(v3, v4) }
        try {
            val lines = listOf(
                "GET https://api.themoviedb.org/3/movie/1?api_key=$v3 failed",
                "request header Authorization: Bearer $v4",
                "TMDB rejected $v3",
                "TMDB rejected $v4",
            )
            for (line in lines) {
                val out = AppLog.redact(line)
                assertFalse(out, out.contains(v3))
                assertFalse(out, out.contains(v4))
            }
        } finally {
            AppLog.secrets = { emptyList() }
        }
    }

    @Test
    fun aKeyAfterApiKeyEqualsIsMaskedEvenWhenItIsNoLongerSaved() {
        AppLog.secrets = { emptyList() }
        assertFalse(AppLog.redact("https://api.themoviedb.org/3/configuration?api_key=$v3").contains(v3))
        assertFalse(AppLog.redact("https://api.mdblist.com/tmdb/movie/550?apikey=abc123def").contains("abc123def"))
    }
}
