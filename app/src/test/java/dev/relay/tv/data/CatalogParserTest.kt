package dev.relay.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogParserTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeAll(dir: File, overrides: Map<String, String> = emptyMap()) {
        val base = mapOf(
            "get_live_categories" to """[{"category_id":"1","category_name":"News"},{"category_id":"9","category_name":"XXX Adult"}]""",
            "get_vod_categories" to """[{"category_id":"2","category_name":"Films"}]""",
            "get_series_categories" to """[{"category_id":"3","category_name":"Drama"}]""",
            "get_live_streams" to """[{"num":1,"name":"News 1","stream_id":101,"category_id":"1","tv_archive":1,"tv_archive_duration":"3"},
                {"num":"2","name":"News 2","stream_id":"102","category_id":"1"}]""",
            "get_vod_streams" to """[{"name":"Film (2019)","stream_id":201,"category_id":"2","rating":"7.5","added":"100"}]""",
            "get_series" to """[{"name":"Show","series_id":301,"category_id":"3","releaseDate":"2021-05-01"}]""",
        )
        (base + overrides).forEach { (k, v) -> File(dir, "$k.json").writeText(v) }
    }

    @Test
    fun progressRisesToTheEndAndEveryListIsTimed() {
        val dir = tmp.newFolder()
        writeAll(dir)
        val seen = mutableListOf<Float>()
        val p = CatalogParser.parse(dir, strict = true) { seen += it }
        assertEquals(1f, seen.last(), 0.0001f)
        assertEquals(seen, seen.sorted())
        assertEquals(6, p.timings.size)
        assertTrue(p.timingText().contains("movie"))
    }

    @Test
    fun validListsParse() {
        val dir = tmp.newFolder()
        writeAll(dir)
        val p = CatalogParser.parse(dir, strict = true)
        assertEquals(CatalogCounts(2, 1, 1), p.counts)
        assertEquals(3, p.channels.first().archiveDays)
        assertEquals("2019", p.movies.first().year)
        assertEquals("2021", p.series.first().year)
        assertTrue(p.liveCats.first { it.id == "9" }.adult)
    }

    @Test
    fun anHtmlErrorPageIsRejected() {
        val dir = tmp.newFolder()
        writeAll(dir, mapOf("get_vod_streams" to "<html><body>502 Bad Gateway</body></html>"))
        expectInvalid(dir, "movie")
    }

    @Test
    fun aJsonErrorObjectIsRejected() {
        val dir = tmp.newFolder()
        writeAll(dir, mapOf("get_live_streams" to """{"user_info":{"auth":0}}"""))
        expectInvalid(dir, "channel")
    }

    @Test
    fun aTruncatedDownloadIsRejected() {
        val dir = tmp.newFolder()
        writeAll(dir, mapOf("get_series" to """[{"name":"Show","series_id":301},{"name":"Sh"""))
        expectInvalid(dir, "series")
    }

    @Test
    fun aMissingFileIsRejected() {
        val dir = tmp.newFolder()
        writeAll(dir)
        File(dir, "get_vod_categories.json").delete()
        expectInvalid(dir, "movie category")
    }

    @Test
    fun lenientModeStillReadsWhatItCan() {
        val dir = tmp.newFolder()
        writeAll(dir, mapOf("get_vod_streams" to "<html>oops</html>"))
        val p = CatalogParser.parse(dir, strict = false)
        assertEquals(0, p.movies.size)
        assertEquals(2, p.channels.size)
    }

    @Test
    fun anEmptyListAfterAFullOneIsRejected() {
        val v = RefreshGuard.check(CatalogCounts(500, 2000, 300), CatalogCounts(500, 0, 300))
        assertFalse(v.accept)
        assertTrue(v.message!!.contains("movies"))
    }

    @Test
    fun aFirstLoadOrNormalChangeIsAccepted() {
        assertTrue(RefreshGuard.check(CatalogCounts(0, 0, 0), CatalogCounts(0, 0, 0)).accept)
        val v = RefreshGuard.check(CatalogCounts(500, 2000, 300), CatalogCounts(480, 2100, 310))
        assertTrue(v.accept)
        assertTrue(v.warnings.isEmpty())
    }

    @Test
    fun aHugeDropIsAcceptedButLogged() {
        val v = RefreshGuard.check(CatalogCounts(5000, 2000, 300), CatalogCounts(100, 2000, 300))
        assertTrue(v.accept)
        assertEquals(1, v.warnings.size)
    }

    @Test
    fun aValidRefreshReplacesAllSixFiles() {
        val live = tmp.newFolder()
        writeAll(live)
        val staging = tmp.newFolder()
        writeAll(staging, mapOf("get_vod_streams" to """[{"name":"New film","stream_id":999,"category_id":"2"}]"""))
        CatalogParser.commit(staging, live)
        assertFalse(staging.exists())
        assertEquals(999, CatalogParser.parse(live, strict = true).movies.single().id)
    }

    @Test
    fun anIncompleteStagingFolderChangesNothing() {
        val live = tmp.newFolder()
        writeAll(live)
        val before = File(live, "get_vod_streams.json").readText()
        val staging = tmp.newFolder()
        writeAll(staging, mapOf("get_vod_streams" to "[]"))
        File(staging, "get_series.json").delete()
        try {
            CatalogParser.commit(staging, live)
            fail("expected the commit to refuse")
        } catch (_: java.io.IOException) {
        }
        assertEquals(before, File(live, "get_vod_streams.json").readText())
    }

    @Test
    fun libraryRefreshesAfterSixHoursAndNotBefore() {
        val saved = 1_000_000L
        assertFalse(Freshness.libraryDue(saved, saved + 5 * 3600_000L))
        assertFalse(Freshness.libraryDue(saved, saved + 6 * 3600_000L - 1))
        assertTrue(Freshness.libraryDue(saved, saved + 6 * 3600_000L))
        assertTrue(Freshness.libraryDue(0L, saved))
    }

    @Test
    fun readingTheBigListsAtOnceGivesTheSameLibrary() {
        val dir = tmp.newFolder()
        val channels = (1..300).joinToString(",", "[", "]") { """{"num":$it,"name":"Ch $it","stream_id":$it,"category_id":"${it % 7}"}""" }
        val movies = (1..300).joinToString(",", "[", "]") { """{"name":"Film $it (20${10 + it % 10})","stream_id":$it,"category_id":"${it % 5}","container_extension":"mkv","rating":"${it % 10}","added":"$it"}""" }
        val series = (1..300).joinToString(",", "[", "]") { """{"name":"Show $it","series_id":$it,"category_id":"${it % 3}","releaseDate":"20${10 + it % 10}-01-01","plot":"long text $it"}""" }
        writeAll(dir, mapOf("get_live_streams" to channels, "get_vod_streams" to movies, "get_series" to series))
        val one = CatalogParser.parse(dir, strict = true)
        for (limit in listOf(1, 2, 3)) {
            val seen = mutableListOf<Float>()
            val two = runBlocking { CatalogParser.parseConcurrently(dir, strict = true, limit = limit) { seen += it } }
            assertEquals("limit $limit", one.channels, two.channels)
            assertEquals("limit $limit", one.movies, two.movies)
            assertEquals("limit $limit", one.series, two.series)
            assertEquals(one.liveCats, two.liveCats)
            assertEquals(one.vodCats, two.vodCats)
            assertEquals(one.seriesCats, two.seriesCats)
            assertEquals(one.timings.keys.toList(), two.timings.keys.toList())
            assertEquals(1f, seen.last(), 0.0001f)
            assertEquals("progress never goes back", seen, seen.sorted())
        }
    }

    @Test
    fun readingAtOnceStillRejectsABadList() {
        val dir = tmp.newFolder()
        writeAll(dir, mapOf("get_vod_streams" to "<html><body>502 Bad Gateway</body></html>"))
        try {
            runBlocking { CatalogParser.parseConcurrently(dir, strict = true) }
            fail("Expected the movie list to be rejected")
        } catch (e: InvalidListException) {
            assertTrue(e.message.orEmpty().contains("movie"))
        }
    }

    @Test
    fun repeatedValuesShareOneCopy() {
        val dir = tmp.newFolder()
        val movies = (1..50).joinToString(",", "[", "]") { """{"name":"Film $it","stream_id":$it,"category_id":"${(10 + it % 3)}","container_extension":"mkv","year":"2021"}""" }
        writeAll(dir, mapOf("get_vod_streams" to movies))
        val p = CatalogParser.parse(dir, strict = true)
        val sameCat = p.movies.filter { it.categoryId == "10" }
        assertTrue(sameCat.size > 2)
        assertTrue("one string instance for the category", sameCat.all { it.categoryId === sameCat.first().categoryId })
        assertTrue("one string instance for the file type", p.movies.all { it.ext === p.movies.first().ext })
        assertTrue("one string instance for the year", p.movies.all { it.year === p.movies.first().year })
        val ix = CatalogParser.Interner()
        assertEquals(null, ix.of(null))
        assertTrue(ix.of(String(charArrayOf('a', 'b'))) === ix.of(String(charArrayOf('a', 'b'))))
        assertEquals(1, ix.size)
    }

    private fun expectInvalid(dir: File, label: String) {
        try {
            CatalogParser.parse(dir, strict = true)
            fail("expected the $label list to be rejected")
        } catch (e: InvalidListException) {
            assertEquals(label, e.list)
        }
    }
}
