package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentKindsTest {

    @Test
    fun liveCategoriesRecogniseSportsNewsMoviesKidsDocsAndMusic() {
        assertEquals(ContentKinds.SPORTS, ContentKinds.ofLiveCategory("UK | Sports"))
        assertEquals(ContentKinds.SPORTS, ContentKinds.ofLiveCategory("[VIP] Premier League & UFC"))
        assertEquals(ContentKinds.NEWS, ContentKinds.ofLiveCategory("USA: News & Business"))
        assertEquals(ContentKinds.MOVIES, ContentKinds.ofLiveCategory("IN - Hindi Movies"))
        assertEquals(ContentKinds.KIDS, ContentKinds.ofLiveCategory("CA | Kids & Family"))
        assertEquals(ContentKinds.DOCS, ContentKinds.ofLiveCategory("UK: Documentary & Nature"))
        assertEquals(ContentKinds.MUSIC, ContentKinds.ofLiveCategory("IN | Bangla Sangeet"))
        assertEquals(ContentKinds.ENTERTAINMENT, ContentKinds.ofLiveCategory("UK | General Entertainment"))
        assertNull(ContentKinds.ofLiveCategory("BANGLADESH"))
    }

    @Test
    fun channelNamesOverrideBroadRegionalCategories() {
        val regional = ContentKinds.ofLiveCategory("INDIA | BENGALI")
        assertNull(regional)
        assertEquals(ContentKinds.SPORTS, ContentKinds.ofChannel("IN: Star Sports 1 HD", regional))
        assertEquals(ContentKinds.SPORTS, ContentKinds.ofChannel("BD: T Sports FHD", regional))
        assertEquals(ContentKinds.NEWS, ContentKinds.ofChannel("ABP Ananda HD", regional))
        assertEquals(ContentKinds.NEWS, ContentKinds.ofChannel("BD: Somoy TV", regional))
        assertEquals(ContentKinds.MOVIES, ContentKinds.ofChannel("Jalsha Movies HD", regional))
        assertEquals(ContentKinds.KIDS, ContentKinds.ofChannel("Cartoon Network HD", regional))
        assertEquals(ContentKinds.DOCS, ContentKinds.ofChannel("National Geographic ⁴ᴷ", regional))
        assertEquals(ContentKinds.MUSIC, ContentKinds.ofChannel("Sangeet Bangla", regional))
        // A generic channel inside a Sports category inherits the category's type.
        assertEquals(ContentKinds.SPORTS, ContentKinds.ofChannel("Main Event 1", ContentKinds.SPORTS))
    }

    @Test
    fun vodAndSeriesCategoriesRecogniseGenresAndIgnoreAdultCategories() {
        val cats = listOf(
            Category("1", "EN - Action & Adventure"),
            Category("2", "HI - Stand-Up & Comedy"),
            Category("3", "4K Documentary"),
            Category("4", "Kids & Animation"),
            Category("5", "WWE & Sports Replays"),
            Category("6", "Sci-Fi & Fantasy"),
            Category("7", "Crime & Thriller"),
            Category("8", "Adult Movies", adult = true),
            Category("9", "2026 New Releases"),
        )
        val map = ContentKinds.titleCategoryKinds(cats)
        assertEquals(ContentKinds.ACTION, map["1"])
        assertEquals(ContentKinds.COMEDY, map["2"])
        assertEquals(ContentKinds.DOCS, map["3"])
        assertEquals(ContentKinds.KIDS, map["4"])
        assertEquals(ContentKinds.SPORTS, map["5"])
        assertEquals(ContentKinds.SCIFI, map["6"])
        assertEquals(ContentKinds.THRILLER, map["7"])
        assertNull("adult categories never appear in content-type filters", map["8"])
        assertNull("recency categories stay general", map["9"])
    }
}
