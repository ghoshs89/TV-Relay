package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelKeysTest {
    @Test
    fun splitsCountryTag() {
        assertEquals("CA" to "CBC CALGARY", ChannelKeys.split("CA: CBC CALGARY"))
        assertEquals("UK" to "BBC One", ChannelKeys.split("[UK] | BBC One"))
        assertEquals("IN" to "Star Plus", ChannelKeys.split("IN - Star Plus"))
    }

    @Test
    fun leavesNamesWithoutATag() {
        assertEquals(null to "Star Plus HD", ChannelKeys.split("Star Plus HD"))
        // A tag with nothing after it is not a tag.
        assertEquals(null to "CA:", ChannelKeys.split("CA:"))
        assertNull(ChannelKeys.split("Zee TV").first)
    }

    @Test
    fun sortKeyDropsTagPunctuationAndCaseButKeepsSpaces() {
        assertEquals("cbc  calgary", ChannelKeys.sortKey("CA: CBC  CALGARY"))
        assertEquals("star plus hd", ChannelKeys.sortKey("★ Star Plus HD ★"))
        assertEquals("zee tv", ChannelKeys.sortKey("Zee TV"))
        assertEquals("", ChannelKeys.sortKey("---"))
    }

    @Test
    fun hdMarkers() {
        assertTrue(ChannelKeys.isHd("Star Plus HD"))
        assertTrue(ChannelKeys.isHd("Sky Sports 4K"))
        assertTrue(ChannelKeys.isHd("ESPN | FHD"))
        assertTrue(ChannelKeys.isHd("Movies hevc"))
        assertFalse(ChannelKeys.isHd("Shdow TV"))
        assertFalse(ChannelKeys.isHd("Zee TV"))
    }
}
