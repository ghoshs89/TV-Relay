package dev.relay.tv.ui

import dev.relay.tv.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveBrowseTest {

    @Test
    fun synonymsMergeIntoCanonicalCountryFolder() {
        val cats = listOf(
            Category("1", "IN | Hindi Entertainment"),
            Category("2", "IND | News"),
            Category("3", "INDIA - Sports"),
            Category("4", "USA: Locals"),
            Category("5", "US | Sports"),
            Category("6", "GB | Cinema"),
            Category("7", "UK | News"),
        )
        val groups = LiveBrowse.group(cats)
        assertNotNull(groups)
        val byKey = groups!!.associateBy { it.key }
        assertEquals("India", byKey["IN"]?.label)
        assertEquals(listOf("Hindi Entertainment", "News", "Sports"), byKey["IN"]?.cats?.map { it.name })
        assertEquals("USA", byKey["US"]?.label)
        assertEquals(2, byKey["US"]?.cats?.size)
        assertEquals("UK", byKey["UK"]?.label)
        assertEquals(2, byKey["UK"]?.cats?.size)
    }

    @Test
    fun multiSegmentRegionalPrefixesNormalizeUnderCountry() {
        assertEquals("IN" to "Hindi", LiveBrowse.split("ASIA | INDIA - Hindi"))
        assertEquals("PK" to "Drama", LiveBrowse.split("ASIA | PK: Drama"))
        assertEquals("ASIA" to "Cricket", LiveBrowse.split("ASIA | Cricket"))
        assertEquals("INTL" to "Documentaries", LiveBrowse.split("INTERNATIONAL | Documentaries"))
    }
}
