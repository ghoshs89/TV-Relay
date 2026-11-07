package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    private data class Ch(val id: Int, val name: String)

    private fun key(n: String) = ChannelVersions.key(n)

    @Test
    fun qualityWordsDoNotSplitAChannel() {
        assertEquals(key("UK: Sky News"), key("UK: Sky News HD"))
        assertEquals(key("UK: Sky News"), key("UK | Sky News FHD"))
        assertEquals(key("UK: Sky News"), key("UK: SKY NEWS ᴴᴰ"))
        assertEquals(key("Star Jalsha HD"), key("Star Jalsha (Backup)"))
        assertEquals(key("Zee Bangla 1080p"), key("Zee Bangla HEVC"))
    }

    @Test
    fun numbersAndCountriesStayApart() {
        assertNotEquals(key("Star Sports 1 HD"), key("Star Sports 2 HD"))
        assertNotEquals(key("UK: CNN"), key("US: CNN"))
        assertNotEquals(key("HD"), key("SD"))
        assertTrue(key("HD").isNotEmpty())
    }

    @Test
    fun qualityRanks() {
        assertTrue(ChannelVersions.quality("X 4K") > ChannelVersions.quality("X FHD"))
        assertTrue(ChannelVersions.quality("X FHD") > ChannelVersions.quality("X HD"))
        assertTrue(ChannelVersions.quality("X HD") > ChannelVersions.quality("X"))
        assertTrue(ChannelVersions.quality("X") > ChannelVersions.quality("X SD"))
        assertTrue(ChannelVersions.quality("X HD Backup") < ChannelVersions.quality("X HD"))
        assertEquals(30, ChannelVersions.quality("Sony Max 1080p"))
        assertEquals("Full HD · HEVC", ChannelVersions.describe("UK: Sky News FHD HEVC"))
        assertEquals("Standard", ChannelVersions.describe("UK: Sky News"))
    }

    @Test
    fun collapseKeepsOnePerChannelAtTheFirstPosition() {
        val list = listOf(Ch(1, "UK: Sky News SD"), Ch(2, "UK: BBC One"), Ch(3, "UK: Sky News FHD"), Ch(4, "UK: Sky News HD"))
        val rank = { c: Ch -> -ChannelVersions.quality(c.name) }
        val out = ChannelVersions.collapse(list, { key(it.name) }, rank, { it.id }) { null }
        assertEquals(listOf(3, 2), out.map { it.id })
        val chosen = ChannelVersions.collapse(list, { key(it.name) }, rank, { it.id }) { 4 }
        assertEquals(listOf(4, 2), chosen.map { it.id })
        // A chosen version that isn't in this list falls back to the best one that is.
        val absent = ChannelVersions.collapse(list, { key(it.name) }, rank, { it.id }) { 99 }
        assertEquals(listOf(3, 2), absent.map { it.id })
        // No duplicates: the same list comes back.
        val single = listOf(Ch(1, "UK: A"), Ch(2, "UK: B"))
        assertTrue(ChannelVersions.collapse(single, { key(it.name) }, rank, { it.id }) { null } === single)
    }

    @Test
    fun orderPutsYourChoiceFirstThenQuality() {
        val l = listOf(Ch(1, "A SD"), Ch(2, "A FHD"), Ch(3, "A HD"))
        assertEquals(listOf(2, 3, 1), ChannelVersions.order(l, { it.name }, { it.id }, null).map { it.id })
        assertEquals(listOf(1, 2, 3), ChannelVersions.order(l, { it.name }, { it.id }, 1).map { it.id })
    }

    @Test
    fun groupsOnlyRealDuplicates() {
        val l = listOf(Ch(1, "A HD"), Ch(2, "A"), Ch(3, "B"))
        val g = ChannelVersions.group(l) { key(it.name) }
        assertEquals(1, g.size)
        assertEquals(listOf(1, 2), g.values.first().map { it.id })
    }
}
