package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbParseTest {
    private fun j(s: String) = Jsons.json.parseToJsonElement(s)

    @Test
    fun recommendationsKeepIdsTitlesAndYears() {
        val recs = Tmdb.parseRecs(j("""{"results":[{"id":1,"title":"Arrival","release_date":"2016-11-10","vote_average":7.6},{"id":2,"name":"Dark","first_air_date":"2017-12-01","media_type":"tv"},{"title":"no id"}]}"""), tv = false)
        assertEquals(listOf(Tmdb.Rec("1", false, "Arrival", "2016", 7.6), Tmdb.Rec("2", true, "Dark", "2017", null)), recs)
    }

    @Test
    fun theLogoInTheTitlesLanguageWinsThenEnglishThenNoText() {
        val logos = j("""[{"file_path":"/en.png","iso_639_1":"en","vote_average":9},{"file_path":"/hi.png","iso_639_1":"hi","vote_average":1},{"file_path":"/x.svg","iso_639_1":"hi","vote_average":10}]""")
        assertEquals("/hi.png", Tmdb.pickLogo((logos as kotlinx.serialization.json.JsonArray).toList(), "hi"))
        assertEquals("/en.png", Tmdb.pickLogo(logos.toList(), "fr"))
    }
}
