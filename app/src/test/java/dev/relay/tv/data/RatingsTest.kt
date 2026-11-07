package dev.relay.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Calendar

class RatingsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun json(s: String) = Jsons.json.parseToJsonElement(s)

    // ---------- Response parsing (recorded MDBList-style replies) ----------

    @Test
    fun bothScores() {
        val r = MdbList.parseItem(200, json(
            """{"title":"Film","year":2019,"ratings":[{"source":"imdb","value":7.9},
               {"source":"tomatoes","value":93,"votes":300},{"source":"popcorn","value":88}]}""",
        ))
        assertEquals(MdbList.Reply.Found(93, 88, 7.9), r)
    }

    @Test
    fun imdbIsKeptForTitlesWithoutRottenTomatoes() {
        val r = MdbList.parseItem(200, json("""{"ratings":[{"source":"imdb","value":8.1},{"source":"tomatoes","value":null}]}"""))
        assertEquals(MdbList.Reply.Found(null, null, 8.1), r)
        assertFalse(Score(imdb = 8.1).none)
    }

    @Test
    fun olderAudienceSourceName() {
        val r = MdbList.parseItem(200, json("""{"ratings":[{"source":"tomatoesaudience","value":71}]}"""))
        assertEquals(MdbList.Reply.Found(null, 71), r)
    }

    @Test
    fun criticsOnlyNoneNullAndZero() {
        assertEquals(MdbList.Reply.Found(55, null), MdbList.parseItem(200, json("""{"ratings":[{"source":"tomatoes","value":55}]}""")))
        assertEquals(MdbList.Reply.Found(null, null), MdbList.parseItem(200, json("""{"ratings":[]}""")))
        assertEquals(MdbList.Reply.Found(null, null), MdbList.parseItem(200, json("""{"ratings":[{"source":"tomatoes","value":null}]}""")))
        assertEquals(MdbList.Reply.Found(null, null), MdbList.parseItem(200, json("""{"ratings":[{"source":"tomatoes","value":0,"votes":0}]}""")))
    }

    @Test
    fun errorsLimitsAndMissingTitles() {
        assertEquals(MdbList.Reply.LimitReached, MdbList.parseItem(429, null))
        assertEquals(MdbList.Reply.LimitReached, MdbList.parseItem(200, json("""{"response":false,"error":"API Limit Reached!"}""")))
        assertEquals(MdbList.Reply.NotFound, MdbList.parseItem(404, json("""{"detail":"x"}""")))
        assertTrue(MdbList.parseItem(200, json("""{"response":false,"error":"Invalid API key!"}""")) is MdbList.Reply.Failed)
        assertTrue(MdbList.parseItem(200, null) is MdbList.Reply.Failed)
    }

    // ---------- Matching by title and year ----------

    @Test
    fun oneCandidateWithTheRightYearMatches() {
        val root = json("""{"search":[{"title":"Dune","year":2021,"ids":{"tmdb":438631}},{"title":"Dune","year":1984,"ids":{"tmdb":841}}]}""")
        assertEquals("438631", MdbList.pickSearch(root, "Dune", 2021))
        assertEquals("841", MdbList.pickSearch(root, "Dune", 1984))
    }

    @Test
    fun aRemakeWithAnotherYearOrTwoCandidatesIsRejected() {
        val root = json("""{"search":[{"title":"Dune","year":2021,"ids":{"tmdb":438631}},{"title":"Dune","year":1984,"ids":{"tmdb":841}}]}""")
        assertNull(MdbList.pickSearch(root, "Dune", 2000))
        assertNull(MdbList.pickSearch(root, "Dune", null))
        val close = json("""{"search":[{"title":"A","year":2020,"ids":{"tmdb":1}},{"title":"A","year":2021,"ids":{"tmdb":2}}]}""")
        assertNull(MdbList.pickSearch(close, "A", 2020))
    }

    // ---------- Cache and request budget ----------

    private var now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 10, 12, 0) }.timeInMillis
    private val day = RatingsCache.DAY

    @Test
    fun recentReleasesRefreshAfterADayOlderAfterAWeekMissingAfterThree() {
        val c = RatingsCache(null, { now })
        val recent = Score(90, 80, fetched = now, year = 2026)
        val older = Score(90, 80, fetched = now, year = 2001)
        val none = Score(fetched = now, year = 2001)
        now += day - 1
        assertTrue(c.isFresh(recent))
        now += 1
        assertFalse(c.isFresh(recent))
        assertTrue(c.isFresh(older))
        assertTrue(c.isFresh(none))
        now += 2 * day
        assertFalse(c.isFresh(none))
        assertTrue(c.isFresh(older))
        now += 4 * day
        assertFalse(c.isFresh(older))
    }

    @Test
    fun lookupsStopAtTheDailyBudgetAndResumeTomorrow() {
        val c = RatingsCache(null, { now })
        repeat(RatingsCache.DAILY_LIMIT) { assertTrue(c.spend()) }
        assertFalse(c.spend())
        now += day
        assertTrue(c.spend())
    }

    @Test
    fun aLimitReplyPausesUntilTomorrow() {
        val c = RatingsCache(null, { now })
        assertTrue(c.spend())
        c.pauseToday()
        assertFalse(c.spend())
        now += day
        assertTrue(c.spend())
    }

    @Test
    fun savedScoresSurviveARestartAndTheCacheIsCapped() {
        val f = tmp.newFile("ratings.json")
        val c = RatingsCache(f, { now }, maxEntries = 20)
        repeat(30) { i -> c.put("m:$i", Score(50, 50, fetched = now + i)) }
        val again = RatingsCache(f, { now }, maxEntries = 20)
        assertNull(again.get("m:0"))
        assertNull(again.get("m:9"))
        assertEquals(50, again.get("m:10")!!.critics)
        assertEquals(50, again.get("m:29")!!.critics)
    }

    // ---------- End to end against a local server ----------

    @Test
    fun lookupUsesTheTmdbIdSavesTheScoreAndKeepsTheKeyOutOfLogs() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/tmdb/movie/603") ->
                    MockResponse().setBody("""{"ratings":[{"source":"tomatoes","value":83},{"source":"popcorn","value":85}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val r = Ratings(OkHttpClient(), tmp.newFile("r.json"), { now })
            r.base = server.url("").toString().trimEnd('/')
            val s = r.lookup("SECRETKEY123", "m:1", tv = false, tmdbId = "603", title = "The Matrix", year = "1999")
            assertEquals(83, s?.critics)
            assertEquals(85, s?.audience)
            // Second call is served from the cache.
            r.lookup("SECRETKEY123", "m:1", false, "603", "The Matrix", "1999")
            assertEquals(1, server.requestCount)
            assertEquals(83, r.saved("m:1")?.critics)
        } finally {
            server.shutdown()
        }
        AppLog.secrets = { listOf("SECRETKEY123") }
        val line = AppLog.redact("GET https://api.mdblist.com/tmdb/movie/603?apikey=SECRETKEY123 failed")
        assertFalse(line.contains("SECRETKEY123"))
        val other = AppLog.redact("GET /x?apikey=abcdefg")
        assertFalse(other.contains("abcdefg"))
    }
}
