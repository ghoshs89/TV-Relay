package dev.relay.tv.data

import dev.relay.tv.ui.LiveBrowse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {

    @Test
    fun titlesLoseProviderClutter() {
        val a = TitleParts.parse("ES - Nocturno (2026) (US)")
        assertEquals("Nocturno", a.title)
        assertEquals("ES", a.tag)
        assertEquals("2026", a.year)
        assertEquals("US", a.region)

        val b = TitleParts.parse("SO-IN - Ghamasaan (2024)")
        assertEquals("Ghamasaan", b.title)
        assertEquals("SO-IN", b.tag)
        assertEquals("2024", b.year)

        val c = TitleParts.parse("EN - Amanda Knox (2016)")
        assertEquals("Amanda Knox", c.title)
        assertEquals("EN", c.tag)
    }

    @Test
    fun ordinaryTitlesAreLeftAlone() {
        assertEquals("The Ark", TitleParts.parse("The Ark").title)
        assertEquals("Spider-Man: No Way Home", TitleParts.parse("Spider-Man: No Way Home").title)
        val m = TitleParts.parse("MONSTER - The Ed Gein Story")
        assertNull(m.tag)
        assertEquals("MONSTER - The Ed Gein Story", m.title)
    }

    @Test
    fun aKnownYearWins() {
        assertEquals("2019", TitleParts.parse("HI - Film (2018)", knownYear = "2019").year)
        assertEquals("2026 · ES · US", TitleParts.parse("ES - Nocturno (2026) (US)").meta())
    }

    @Test
    fun categoryAndChannelNamesAreReadable() {
        assertEquals("OSN+ SERIES 4K", ProviderNames.clean("OSN+ SERIES ⁴ᴷ ³⁸⁴⁰ᴾ"))
        assertEquals("4K: ELEVEN SPORTS 1 UHD", ProviderNames.clean("4K: ELEVEN SPORTS 1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals("NEWS", ProviderNames.clean("★ NEWS ★"))
        assertEquals("UK | BBC One", ProviderNames.clean("UK | BBC One"))
        assertEquals("Netflix Series", ProviderNames.clean("Netflix Series"))
    }

    @Test
    fun countryCodesHaveRealNames() {
        assertEquals("HR" to "Sports", LiveBrowse.split("HR | Sports"))
        assertEquals("Croatia", LiveBrowse.label("HR"))
        // Unknown codes aren't treated as countries.
        assertNull(LiveBrowse.split("TS | Something"))
        assertEquals("India", LiveBrowse.label("IN"))
    }
}
