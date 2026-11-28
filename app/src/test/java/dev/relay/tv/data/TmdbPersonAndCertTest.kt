package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbPersonAndCertTest {

    @Test
    fun parsesMovieAndTvCertificationsWithRegionPriority() {
        val movieJson = Jsons.json.parseToJsonElement(
            """
            {
              "release_dates": {
                "results": [
                  { "iso_3166_1": "DE", "release_dates": [{ "certification": "6", "type": 3 }] },
                  { "iso_3166_1": "US", "release_dates": [{ "certification": "", "type": 1 }, { "certification": "PG", "type": 3 }] }
                ]
              }
            }
            """.trimIndent(),
        )
        assertEquals("PG", Tmdb.parseCertification(movieJson, tv = false))

        val tvJson = Jsons.json.parseToJsonElement(
            """
            {
              "content_ratings": {
                "results": [
                  { "iso_3166_1": "GB", "rating": "U" },
                  { "iso_3166_1": "US", "rating": "TV-Y7" }
                ]
              }
            }
            """.trimIndent(),
        )
        assertEquals("TV-Y7", Tmdb.parseCertification(tvJson, tv = true))
    }

    @Test
    fun parsesCombinedCreditsPrioritizingActingRolesOverTalkShows() {
        val json = Jsons.json.parseToJsonElement(
            """
            {
              "id": 500,
              "name": "Tom Cruise",
              "profile_path": "/abc.jpg",
              "biography": "American actor.",
              "known_for_department": "Acting",
              "combined_credits": {
                "cast": [
                  { "id": 10, "media_type": "tv", "name": "Graham Norton", "first_air_date": "2010-01-01", "character": "Self", "genre_ids": [10767], "popularity": 999.0, "vote_count": 500 },
                  { "id": 361743, "media_type": "movie", "title": "Top Gun: Maverick", "release_date": "2022-05-24", "character": "Pete Mitchell", "genre_ids": [28], "vote_average": 8.2, "popularity": 120.0, "vote_count": 8000 },
                  { "id": 361743, "media_type": "movie", "title": "Top Gun: Maverick", "release_date": "2022-05-24", "character": "Producer", "genre_ids": [28], "popularity": 50.0, "vote_count": 100 }
                ]
              }
            }
            """.trimIndent(),
        )
        val pc = Tmdb.parsePersonCredits("500", json)
        assertEquals("500", pc.id)
        assertEquals("Tom Cruise", pc.name)
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", pc.photo)
        assertEquals("American actor.", pc.bio)
        assertEquals(2, pc.credits.size)
        // Deduplicated and Top Gun ranked ahead of talk show "Self" appearance.
        assertEquals("Top Gun: Maverick", pc.credits[0].title)
        assertEquals("2022", pc.credits[0].year)
        assertEquals(listOf(28), pc.credits[0].genreIds)
        assertEquals("Graham Norton", pc.credits[1].title)
    }

    @Test
    fun parsesMdbListCertificationLanguageCountryAndGenres() {
        val json = Jsons.json.parseToJsonElement(
            """
            {
              "title": "Inception",
              "year": 2010,
              "certification": "PG-13",
              "language": "en",
              "country": "us",
              "genres": [{"id": 28, "title": "Action"}, {"id": 878, "title": "Science Fiction"}],
              "ratings": [{"source": "tomatoes", "value": 87}, {"source": "imdb", "value": 8.8}]
            }
            """.trimIndent(),
        )
        val reply = MdbList.parseItem(200, json) as MdbList.Reply.Found
        assertEquals(87, reply.critics)
        assertEquals(8.8, reply.imdb!!, 0.01)
        assertEquals("PG-13", reply.certification)
        assertEquals("en", reply.language)
        assertEquals("us", reply.country)
        assertEquals(listOf("Action", "Science Fiction"), reply.genres)
    }

    @Test
    fun parsesTmdbRecAdultAndGenreIds() {
        val json = Jsons.json.parseToJsonElement(
            """
            {
              "results": [
                { "id": 862, "title": "Toy Story", "release_date": "1995-11-22", "adult": false, "genre_ids": [16, 10751], "vote_average": 8.0 },
                { "id": 999, "title": "Adult Flick", "release_date": "2020-01-01", "adult": true, "genre_ids": [18], "vote_average": 5.0 }
              ]
            }
            """.trimIndent(),
        )
        val recs = Tmdb.parseRecs(json, tv = false)
        assertEquals(2, recs.size)
        assertFalse(recs[0].adult)
        assertEquals(listOf(16, 10751), recs[0].genreIds)
        assertTrue(recs[1].adult)
    }
}
