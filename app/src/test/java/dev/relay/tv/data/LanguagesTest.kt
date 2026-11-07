package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    private val d = LanguageDetector

    @Test
    fun titleTagsDecideTheLanguage() {
        assertEquals(Langs.EN, d.ofTitle("EN - Amanda Knox (2016)", null))
        assertEquals(Langs.HI, d.ofTitle("HI - Pathaan (2023)", null))
        assertEquals(Langs.BN, d.ofTitle("BN - Aparajito (2022)", null))
        assertEquals(Langs.OTHER, d.ofTitle("ES - Nocturno (2026) (US)", null))
        assertNull(d.ofTitle("Untagged Film (2020)", null))
    }

    @Test
    fun southIndianNeverLandsInHindi() {
        assertEquals(Langs.SOUTH, d.ofTitle("SO-IN - Ghamasaan (2024)", Langs.HI))
        assertEquals(Langs.SOUTH, d.ofCategory("SOUTH INDIAN HINDI DUBBED"))
        assertEquals(Langs.SOUTH, d.ofCategory("TAMIL MOVIES"))
        assertEquals(Langs.SOUTH, d.ofChannel("IN: NTV TELUGU", null))
    }

    @Test
    fun bangladeshNeverLandsInBengali() {
        assertEquals(Langs.BD, d.ofCategory("BANGLADESH | ENTERTAINMENT"))
        assertEquals(Langs.BD, d.ofCategory("BD: BANGLA NATOK"))
        assertEquals(Langs.BD, d.ofChannel("Channel i HD", null))
        assertEquals(Langs.BD, d.ofChannel("ATN BANGLA", null))
        assertEquals(Langs.BD, d.ofTitle("BD - Hawa (2022)", null))
    }

    @Test
    fun indianBengaliIsBengali() {
        assertEquals(Langs.BN, d.ofChannel("IN: Star Jalsha HD", null))
        assertEquals(Langs.BN, d.ofChannel("Zee Bangla", null))
        assertEquals(Langs.BN, d.ofCategory("INDIA | BANGLA"))
    }

    @Test
    fun hindiAndEnglishChannelsAndCategories() {
        assertEquals(Langs.HI, d.ofChannel("IN: Star Plus HD", null))
        assertEquals(Langs.HI, d.ofChannel("&TV HD", null))
        assertEquals(Langs.HI, d.ofCategory("HINDI MOVIES"))
        assertEquals(Langs.EN, d.ofCategory("UK | ENTERTAINMENT"))
        assertEquals(Langs.EN, d.ofChannel("UK: BBC One", null))
        assertEquals(Langs.HI, d.ofChannel("Some Channel", Langs.HI))
        assertNull(d.ofCategory("NETFLIX SERIES (MULTI-SUB)"))
    }

    @Test
    fun rankingPutsYourLanguagesFirstAndHidesNothing() {
        val r = LangRank(Langs.DEFAULT_ORDER)
        val items = listOf(Langs.OTHER, null, Langs.BD, Langs.SOUTH, Langs.BN, Langs.HI, Langs.EN)
        val sorted = items.sortedBy { r.rank(it) }
        assertEquals(listOf(Langs.EN, Langs.HI, Langs.BN, Langs.SOUTH, Langs.BD, null, Langs.OTHER), sorted)
        assertEquals(items.size, sorted.size)
    }

    @Test
    fun eachProfileKeepsItsOwnOrder() {
        val kids = LangRank(listOf(Langs.EN))
        val parent = LangRank(listOf(Langs.BN, Langs.HI, Langs.EN))
        assertTrue(kids.rank(Langs.EN) < kids.rank(Langs.HI))
        assertTrue(parent.rank(Langs.BN) < parent.rank(Langs.EN))
    }

    @Test
    fun reportCountsAndListsUnknowns() {
        val r = LanguageReport(
            channels = mapOf(Langs.EN to 3, null to 1),
            movies = mapOf(Langs.HI to 2),
            series = emptyMap(),
            unknownTags = listOf("XX" to 4),
            unknownCategories = listOf("MISC" to 10),
        )
        val t = r.text()
        assertTrue(t.contains("English: 3 (75%)"))
        assertTrue(t.contains("XX: 4"))
        assertTrue(t.contains("MISC: 10"))
    }
}
