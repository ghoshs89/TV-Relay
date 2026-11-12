package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety net for plan 4. The live text rules must give exactly the answers the old ones did
 * ([LegacyText], frozen), for every name in a large corpus and for every category language.
 * Phases A (cheaper rules), B (saved copy stores these facts) and C (facts reused on refresh)
 * all rest on this.
 */
class ChannelFactsGoldenTest {

    private fun <T> check(what: String, names: List<String>, expected: (String) -> T, actual: (String) -> T) {
        val wrong = mutableListOf<String>()
        var count = 0
        for (n in names) {
            val e = expected(n)
            val a = actual(n)
            if (e != a) {
                count++
                if (wrong.size < 8) wrong += "  \"$n\"\n    old: $e\n    new: $a"
            }
        }
        assertTrue("$what differs for $count of ${names.size} names:\n" + wrong.joinToString("\n"), count == 0)
    }

    @Test
    fun corpusIsBigEnoughAndVaried() {
        val names = TextCorpus.channelNames(24_000)
        assertTrue(names.size >= 20_000)
        // Not just one style: tags, quality words, symbols, non-Latin text and empty names are all present.
        assertTrue(names.any { it.isEmpty() })
        assertTrue(names.any { it.contains('ᴿ') })
        assertTrue(names.any { it.contains("🔥") })
        assertTrue(names.any { it.any { c -> c.code > 0x0900 } })
        assertTrue(names.any { it.startsWith("[") })
        assertTrue(names.distinct().size > 10_000)
        // The old rules find a language for a good share, so the keyword matcher is really exercised.
        val hits = names.count { LegacyText.ofChannel(it, null) != null }
        assertTrue("only $hits names have a language", hits > 1_000)
    }

    @Test
    fun deriveEqualsTheOldRulesForEveryNameAndCategoryLanguage() {
        val names = TextCorpus.channelNames(24_000)
        for (cat in TextCorpus.categoryLangs) {
            check("facts with category language $cat", names, { LegacyText.facts(it, cat) }, { ChannelText.derive(it, cat) })
        }
    }

    @Test
    fun publicOneArgumentFunctionsStillAnswerAsBefore() {
        val names = TextCorpus.channelNames(24_000)
        check("ChannelVersions.key", names, LegacyText::key, ChannelVersions::key)
        check("ChannelVersions.quality", names, LegacyText::quality, ChannelVersions::quality)
        check("ChannelKeys.sortKey", names, LegacyText::sortKey, ChannelKeys::sortKey)
        check("ChannelKeys.isHd", names, LegacyText::isHd, ChannelKeys::isHd)
        for (cat in TextCorpus.categoryLangs) {
            check("LanguageDetector.ofChannel ($cat)", names, { LegacyText.ofChannel(it, cat) }, { LanguageDetector.ofChannel(it, cat) })
        }
    }

    @Test
    fun categoryAndTitleLanguagesAreUnchanged() {
        val categories = TextCorpus.categories() + TextCorpus.channelNames(6_000)
        check("LanguageDetector.ofCategory", categories, LegacyText::ofCategory, LanguageDetector::ofCategory)
        val titles = TextCorpus.titles(8_000) + TextCorpus.seedNames + TextCorpus.fuzzed(4_000, seed = 31)
        for (cat in TextCorpus.categoryLangs) {
            check("LanguageDetector.ofTitle ($cat)", titles, { LegacyText.ofTitle(it, cat) }, { LanguageDetector.ofTitle(it, cat) })
        }
    }

    @Test
    fun describeStillMatchesQualityWords() {
        // describe() shares the word list with quality(); it was edited to take the cleaned name too.
        val names = TextCorpus.channelNames(3_000)
        val expected = names.map { n -> LegacyText.describe(n) }
        assertEquals(expected, names.map { ChannelVersions.describe(it) })
    }

    @Test
    fun aFewHandCheckedAnswers() {
        // Not only "old equals new": answers worked out by hand from the rules.
        val f = ChannelText.derive("UK: Sky News HD", null)
        assertEquals("UK:skynews|" + Langs.EN, f.versionKey)
        assertEquals(Langs.EN, f.lang)
        assertEquals(20, f.quality)
        assertEquals("sky news hd", f.sortKey)
        assertTrue(f.hd)
        val g = ChannelText.derive("Star Jalsha FHD", Langs.OTHER)
        assertEquals(Langs.BN, g.lang)
        assertEquals(30, g.quality)
        assertTrue(g.hd)
        val h = ChannelText.derive("", null)
        assertEquals(null, h.lang)
        assertEquals(15, h.quality)
        assertEquals("", h.sortKey)
        assertEquals(false, h.hd)
    }
}
