package dev.relay.tv.data

import dev.relay.tv.data.Trailers.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailersTest {
    private val id1 = "dQw4w9WgXcQ"
    private val id2 = "9bZkp7q19f0"
    private val id3 = "kJQP7kiw5Fk"
    private val id4 = "aB3_dE-gH1J"

    private fun v(
        key: String,
        type: String = "Trailer",
        official: Boolean = true,
        lang: String? = "en",
        site: String = "YouTube",
        published: String? = "2020-01-01T00:00:00.000Z",
    ) = Video(site, type, official, lang, key, published)

    // ---------- youtubeId ----------

    @Test
    fun anIdOnItsOwnIsAccepted() {
        assertEquals(id1, Trailers.youtubeId(id1))
        assertEquals(id1, Trailers.youtubeId("  $id1\n"))
        assertEquals(id4, Trailers.youtubeId(id4))
    }

    @Test
    fun everyAddressFormIsAccepted() {
        assertEquals(id1, Trailers.youtubeId("https://www.youtube.com/watch?v=$id1"))
        assertEquals(id1, Trailers.youtubeId("http://youtube.com/watch?v=$id1&t=30s"))
        assertEquals(id1, Trailers.youtubeId("https://www.youtube.com/watch?feature=share&v=$id1"))
        assertEquals(id1, Trailers.youtubeId("https://m.youtube.com/watch?v=$id1"))
        assertEquals(id1, Trailers.youtubeId("www.youtube.com/watch?v=$id1"))
        assertEquals(id1, Trailers.youtubeId("https://youtu.be/$id1"))
        assertEquals(id1, Trailers.youtubeId("https://youtu.be/$id1?si=abc"))
        assertEquals(id1, Trailers.youtubeId("https://www.youtube.com/embed/$id1"))
        assertEquals(id1, Trailers.youtubeId("https://www.youtube-nocookie.com/embed/$id1?rel=0"))
        assertEquals(id1, Trailers.youtubeId("https://www.youtube.com/shorts/$id1"))
        assertEquals(id1, Trailers.youtubeId("HTTPS://WWW.YOUTUBE.COM/WATCH?V=$id1"))
    }

    @Test
    fun junkIsRejected() {
        assertNull(Trailers.youtubeId(null))
        assertNull(Trailers.youtubeId(""))
        assertNull(Trailers.youtubeId("   "))
        assertNull(Trailers.youtubeId("null"))
        assertNull(Trailers.youtubeId("trailer"))
        assertNull(Trailers.youtubeId("N/A"))
        assertNull(Trailers.youtubeId("not a trailer id"))
        assertNull(Trailers.youtubeId("https://vimeo.com/123456789"))
        assertNull(Trailers.youtubeId("https://www.youtube.com/"))
        assertNull(Trailers.youtubeId("https://www.youtube.com/watch?v=short"))
        assertNull(Trailers.youtubeId("https://www.youtube.com/watch?v=${id1}EXTRA"))
        assertNull(Trailers.youtubeId("$id1$id1"))
        assertNull(Trailers.youtubeId("https://example.com/watch?v=$id1"))
    }

    @Test
    fun anElevenLetterWordOrNumberIsNotAnId() {
        assertNull(Trailers.youtubeId("Documentary"))
        assertNull(Trailers.youtubeId("documentary"))
        assertNull(Trailers.youtubeId("DOCUMENTARY"))
        assertNull(Trailers.youtubeId("12345678901"))
    }

    // ---------- pick ----------

    @Test
    fun noSourcesGiveNothing() {
        assertNull(Trailers.pick(null, emptyList(), "en"))
        assertNull(Trailers.pick("junk", emptyList(), null))
    }

    @Test
    fun onlyYouTubeTrailersAndTeasersCount() {
        val list = listOf(
            v(id1, site = "Vimeo"),
            v(id2, type = "Clip"),
            v(id3, type = "Featurette"),
            v(id4, type = "Behind the Scenes"),
        )
        assertNull(Trailers.pick(null, list, "en"))
        assertEquals(id3, Trailers.pick(null, list + v(id3, type = "Teaser"), "en"))
        assertEquals(id1, Trailers.pick(null, listOf(v(id1, site = "youtube")), "en"))
    }

    @Test
    fun aTrailerBeatsATeaserEvenWhenTheTeaserIsOfficialAndNewer() {
        val list = listOf(
            v(id1, type = "Teaser", official = true, published = "2024-01-01T00:00:00.000Z"),
            v(id2, type = "Trailer", official = false, published = "2019-01-01T00:00:00.000Z"),
        )
        assertEquals(id2, Trailers.pick(null, list, "en"))
    }

    @Test
    fun officialComesBeforeUnofficialOfTheSameKind() {
        val list = listOf(
            v(id1, official = false, published = "2024-01-01T00:00:00.000Z"),
            v(id2, official = true, published = "2019-01-01T00:00:00.000Z"),
        )
        assertEquals(id2, Trailers.pick(null, list, "en"))
    }

    @Test
    fun theTitlesOwnLanguageComesFirstThenEnglishThenAnyOther() {
        val list = listOf(
            v(id1, lang = "fr"),
            v(id2, lang = "en"),
            v(id3, lang = "hi"),
        )
        assertEquals(id3, Trailers.pick(null, list, "hi"))
        assertEquals(id2, Trailers.pick(null, list, "bn"))
        assertEquals(id2, Trailers.pick(null, list, null))
        assertEquals(id1, Trailers.pick(null, listOf(v(id1, lang = "fr"), v(id4, lang = null)), "hi"))
    }

    @Test
    fun theNewestWinsAmongEquals() {
        val list = listOf(
            v(id1, published = "2018-05-01T00:00:00.000Z"),
            v(id2, published = "2021-05-01T00:00:00.000Z"),
            v(id3, published = "2020-05-01T00:00:00.000Z"),
        )
        assertEquals(id2, Trailers.pick(null, list, "en"))
        assertEquals(id1, Trailers.pick(null, listOf(v(id1, published = null), v(id3, published = null)), "en"))
    }

    @Test
    fun tmdbEntriesWithoutAValidIdAreSkipped() {
        val list = listOf(v("short"), v("has spaces!"), v(id1, official = false))
        assertEquals(id1, Trailers.pick(null, list, "en"))
    }

    @Test
    fun theProvidersTrailerIsUsedWhenTmdbHasNone() {
        assertEquals(id1, Trailers.pick(id1, emptyList(), "hi"))
        assertEquals(id1, Trailers.pick("https://youtu.be/$id1", listOf(v(id2, type = "Clip")), "hi"))
        assertNull(Trailers.pick("trailer", emptyList(), "hi"))
    }

    @Test
    fun theProvidersTrailerWinsWhenItIsAsGoodAsTmdbsBest() {
        // Two equally good trailers: the provider's pick is kept over the newer one.
        val list = listOf(
            v(id1, published = "2018-01-01T00:00:00.000Z"),
            v(id2, published = "2022-01-01T00:00:00.000Z"),
        )
        assertEquals(id1, Trailers.pick(id1, list, "en"))
        assertEquals(id1, Trailers.pick("https://www.youtube.com/watch?v=$id1", list, "en"))
    }

    @Test
    fun tmdbsBestWinsWhenTheProvidersIsWorseOrUnknown() {
        val list = listOf(
            v(id1, lang = "en"),
            v(id2, lang = "hi"),
            v(id3, type = "Teaser", lang = "hi"),
        )
        // The provider's English trailer is listed, but the Hindi one suits a Hindi film better.
        assertEquals(id2, Trailers.pick(id1, list, "hi"))
        // The provider's teaser is listed, but a trailer exists.
        assertEquals(id2, Trailers.pick(id3, list, "hi"))
        // The provider's id is not on TMDB's list at all.
        assertEquals(id2, Trailers.pick(id4, list, "hi"))
    }

    @Test
    fun languageCodesAreComparedIgnoringCase() {
        val list = listOf(v(id1, lang = "EN"), v(id2, lang = "HI"))
        assertEquals(id2, Trailers.pick(null, list, "hi"))
        assertEquals(id2, Trailers.pick(null, list, " HI "))
    }

    // ---------- parse ----------

    @Test
    fun readsTmdbsVideosAnswer() {
        val json = Jsons.json.parseToJsonElement(
            """
            {"id": 550, "results": [
              {"iso_639_1": "en", "iso_3166_1": "US", "name": "Official Trailer", "key": "$id1", "site": "YouTube",
               "size": 1080, "type": "Trailer", "official": true, "published_at": "2019-05-20T14:00:12.000Z"},
              {"iso_639_1": "hi", "name": "Clip", "key": "$id2", "site": "YouTube", "type": "Clip", "official": false},
              {"name": "No key", "site": "YouTube", "type": "Trailer"}
            ]}
            """.trimIndent(),
        )
        val list = Trailers.parse(json)
        assertEquals(2, list.size)
        assertEquals(Video("YouTube", "Trailer", true, "en", id1, "2019-05-20T14:00:12.000Z"), list[0])
        assertEquals(Video("YouTube", "Clip", false, "hi", id2, null), list[1])
    }

    @Test
    fun parsingRubbishGivesAnEmptyList() {
        assertEquals(emptyList<Video>(), Trailers.parse(null))
        assertEquals(emptyList<Video>(), Trailers.parse(Jsons.json.parseToJsonElement("{}")))
        assertEquals(emptyList<Video>(), Trailers.parse(Jsons.json.parseToJsonElement("""{"results": "no"}""")))
        assertEquals(emptyList<Video>(), Trailers.parse(Jsons.json.parseToJsonElement("[1,2]")))
    }

    // ---------- addresses ----------

    @Test
    fun buildsTheWatchAddress() {
        assertEquals("https://www.youtube.com/watch?v=$id1", Trailers.watchUrl(id1))
    }

    @Test
    fun buildsASearchAddressFromTheCleanTitleAndYear() {
        assertEquals(
            "https://www.youtube.com/results?search_query=Pather+Panchali+1955+trailer",
            Trailers.searchUrl("Pather Panchali", "1955"),
        )
        assertEquals("https://www.youtube.com/results?search_query=Dune+trailer", Trailers.searchUrl("Dune", null))
        assertEquals("https://www.youtube.com/results?search_query=Dune+trailer", Trailers.searchUrl(" Dune ", "20"))
        assertEquals(
            "https://www.youtube.com/results?search_query=Caf%C3%A9+%26+Co+2001+trailer",
            Trailers.searchUrl("Café & Co", "2001-05-01"),
        )
    }
}
