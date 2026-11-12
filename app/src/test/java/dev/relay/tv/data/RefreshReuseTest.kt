package dev.relay.tv.data

import dev.relay.tv.data.LibraryDerive.PreviousChannels
import dev.relay.tv.data.LibraryDerive.PreviousTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A refresh reuses what was worked out for items that did not change. The reused result must be
 * exactly what working out every item would give, whatever changed: names edited, items moved to
 * another category, a category's language changed, items added or removed, ids listed twice.
 */
class RefreshReuseTest {

    private fun cats(prefix: String, names: List<String>) = names.mapIndexed { i, n -> Category("$prefix$i", n) }

    private fun channels(count: Int, cats: List<Category>, seed: Int): List<Channel> {
        val rnd = Random(seed)
        val names = TextCorpus.channelNames(count + 50)
        return List(count) { i ->
            Channel(10_000 + i, i, names[i], null, cats[rnd.nextInt(cats.size)].id, null, 0)
        }
    }

    private fun movies(count: Int, cats: List<Category>, seed: Int): List<Movie> {
        val rnd = Random(seed)
        val names = TextCorpus.titles(count) + TextCorpus.fuzzed(count, seed = seed)
        return List(count) { i ->
            Movie(50_000 + i, if (i % 3 == 0) names[count + i] else names[i], null, cats[rnd.nextInt(cats.size)].id, 0.0, "mp4", 0L, null, null)
        }
    }

    private fun series(count: Int, cats: List<Category>, seed: Int): List<Series> {
        val rnd = Random(seed)
        val names = TextCorpus.titles(count) + TextCorpus.fuzzed(count, seed = seed)
        return List(count) { i ->
            Series(70_000 + i, if (i % 3 == 0) names[count + i] else names[i], null, cats[rnd.nextInt(cats.size)].id, 0.0, null, 0L, null)
        }
    }

    private val liveCats = cats("L", TextCorpus.categories().take(40))
    private val vodCats = cats("V", TextCorpus.categories().drop(80).take(30))

    /** One category now has a different language, as if the provider renamed it. */
    private fun renamedCats(old: List<Category>): List<Category> =
        old.mapIndexed { i, c -> if (i % 7 == 3) c.copy(name = "HINDI " + c.name) else if (i % 11 == 5) c.copy(name = "BANGLADESH " + c.name) else c }

    private fun same(a: LibraryDerive.ChannelMaps, b: LibraryDerive.ChannelMaps) {
        assertEquals(a.lang, b.lang)
        assertEquals(a.versionKeys, b.versionKeys)
        assertEquals(a.quality, b.quality)
        assertEquals(a.sortKeys, b.sortKeys)
        assertEquals(a.hd, b.hd)
    }

    private fun previous(list: List<Channel>, cats: List<Category>): PreviousChannels {
        val lang = LibraryDerive.categoryLangs(cats)
        return PreviousChannels(list, lang, LibraryDerive.channels(list, lang))
    }

    /** A changed copy of a channel list: some removed, some added, names edited, some moved, an id listed twice, shuffled. */
    private fun changed(old: List<Channel>, cats: List<Category>, seed: Int): List<Channel> {
        val rnd = Random(seed)
        val fresh = TextCorpus.fuzzed(old.size, seed = seed + 1)
        val out = ArrayList<Channel>()
        for ((i, c) in old.withIndex()) {
            when (rnd.nextInt(20)) {
                0 -> Unit // removed
                1, 2 -> out += c.copy(name = fresh[i]) // renamed
                3, 4 -> out += c.copy(name = c.name + " HD") // quality word added
                5, 6 -> out += c.copy(categoryId = cats[rnd.nextInt(cats.size)].id) // moved
                7 -> { out += c; out += c.copy(num = c.num + 1, name = fresh[i]) } // an id listed twice
                else -> out += c
            }
        }
        repeat(old.size / 20) { out += Channel(900_000 + it, 0, fresh[it], null, cats[rnd.nextInt(cats.size)].id, null, 0) } // added
        out.shuffle(rnd)
        return out
    }

    @Test
    fun anUnchangedLibraryReusesEveryChannel() {
        val list = channels(3_000, liveCats, 1)
        val prev = previous(list, liveCats)
        val lang = LibraryDerive.categoryLangs(liveCats)
        val again = LibraryDerive.channels(list, lang, prev)
        assertEquals(list.size, again.reused)
        same(again, prev.maps)
    }

    @Test
    fun withoutAPreviousLibraryNothingIsReused() {
        val list = channels(500, liveCats, 1)
        assertEquals(0, LibraryDerive.channels(list, LibraryDerive.categoryLangs(liveCats)).reused)
    }

    @Test
    fun aChangedLibraryGivesExactlyWhatFullyWorkingItOutGives() {
        for (seed in 1..5) {
            val old = channels(2_500, liveCats, seed)
            val newCats = if (seed % 2 == 0) renamedCats(liveCats) else liveCats
            val new = changed(old, newCats, seed)
            val prev = previous(old, liveCats)
            val newLang = LibraryDerive.categoryLangs(newCats)
            val reused = LibraryDerive.channels(new, newLang, prev)
            val full = LibraryDerive.channels(new, newLang)
            same(reused, full)
            assertTrue("seed $seed reused ${reused.reused} of ${new.size}", reused.reused in 1 until new.size)
        }
    }

    @Test
    fun theReuseCountIsExactlyTheChannelsWhoseNameAndLanguageAreUnchanged() {
        val old = channels(2_500, liveCats, 7)
        val newCats = renamedCats(liveCats)
        val new = changed(old, newCats, 7)
        val prev = previous(old, liveCats)
        val oldLang = LibraryDerive.categoryLangs(liveCats)
        val newLang = LibraryDerive.categoryLangs(newCats)
        val oldById = old.associateBy { it.id } // ids are unique in the old list here
        val expected = new.count { c ->
            val o = oldById[c.id]
            o != null && o.name == c.name && oldLang[o.categoryId] == newLang[c.categoryId]
        }
        assertEquals(expected, LibraryDerive.channels(new, newLang, prev).reused)
        // The category languages really did change for some, so that branch was exercised.
        assertTrue(oldLang != newLang)
    }

    @Test
    fun aCategoryThatChangedLanguageIsWorkedOutAgain() {
        val c1 = Channel(1, 1, "Some Unknown Channel", null, "L0", null, 0)
        val oldCats = listOf(Category("L0", "UK| News"))
        val newCats = listOf(Category("L0", "HINDI News"))
        val prev = previous(listOf(c1), oldCats)
        assertEquals(Langs.EN, prev.maps.lang[1])
        val now = LibraryDerive.channels(listOf(c1), LibraryDerive.categoryLangs(newCats), prev)
        assertEquals(0, now.reused)
        assertEquals(Langs.HI, now.lang[1])
        assertEquals("some unknown channel", prev.maps.sortKeys[1])
    }

    @Test
    fun anIdListedTwiceBeforeIsAlwaysWorkedOut() {
        val a = Channel(5, 1, "UK: Alpha HD", null, "L0", null, 0)
        val b = Channel(5, 2, "UK: Beta", null, "L0", null, 0)
        val other = Channel(6, 3, "UK: Gamma", null, "L0", null, 0)
        val cats = listOf(Category("L0", "UK| News"))
        val prev = previous(listOf(a, b, other), cats)
        val lang = LibraryDerive.categoryLangs(cats)
        val now = LibraryDerive.channels(listOf(a, b, other), lang, prev)
        assertEquals(1, now.reused) // only id 6
        same(now, LibraryDerive.channels(listOf(a, b, other), lang))
    }

    @Test
    fun anIdListedTwiceNowIsHandledLikeAFullWorkOut() {
        val old = listOf(Channel(5, 1, "UK: Alpha HD", null, "L0", null, 0))
        val cats = listOf(Category("L0", "UK| News"))
        val twice = listOf(old[0], old[0].copy(name = "BD: Beta"), old[0].copy(name = "xx"))
        val lang = LibraryDerive.categoryLangs(cats)
        same(LibraryDerive.channels(twice, lang, previous(old, cats)), LibraryDerive.channels(twice, lang))
    }

    @Test
    fun movieAndSeriesLanguagesAreReusedWithTheSameResultAsAFullWorkOut() {
        for (seed in 1..4) {
            val oldMovies = movies(2_000, vodCats, seed)
            val newCats = if (seed % 2 == 0) renamedCats(vodCats) else vodCats
            val rnd = Random(seed)
            val newMovies = oldMovies.filter { rnd.nextInt(15) != 0 }.map { m ->
                when (rnd.nextInt(12)) {
                    0 -> m.copy(name = m.name + " (2020)")
                    1 -> m.copy(categoryId = newCats[rnd.nextInt(newCats.size)].id)
                    2 -> m.copy(name = "HI - " + m.name)
                    else -> m
                }
            } + oldMovies.take(30).map { it.copy(id = it.id + 500_000, name = "BN - New ${it.id}") } + oldMovies[3].copy(name = "dup")
            val oldLang = LibraryDerive.categoryLangs(vodCats)
            val prev = PreviousTitles(oldMovies, oldLang, LibraryDerive.movies(oldMovies, oldLang).lang)
            val newLang = LibraryDerive.categoryLangs(newCats)
            val reused = LibraryDerive.movies(newMovies, newLang, prev)
            val full = LibraryDerive.movies(newMovies, newLang)
            assertEquals(full.lang, reused.lang)
            assertEquals(full.added, reused.added)
            assertEquals(full.rated, reused.rated)
            assertEquals(full.byName, reused.byName)
            assertTrue("seed $seed reused ${reused.reused} of ${newMovies.size}", reused.reused in 1 until newMovies.size)

            val oldSeries = series(1_500, vodCats, seed)
            val newSeries = oldSeries.filter { rnd.nextInt(10) != 0 }.map { if (rnd.nextInt(8) == 0) it.copy(name = "ES - " + it.name) else it }
            val sPrev = PreviousTitles(oldSeries, oldLang, LibraryDerive.series(oldSeries, oldLang).lang)
            val sReused = LibraryDerive.series(newSeries, newLang, sPrev)
            assertEquals(LibraryDerive.series(newSeries, newLang).lang, sReused.lang)
            assertTrue(sReused.reused > 0)
        }
    }

    @Test
    fun anUnchangedLibraryReusesEveryTitleLanguage() {
        val list = movies(1_000, vodCats, 2)
        val lang = LibraryDerive.categoryLangs(vodCats)
        val first = LibraryDerive.movies(list, lang)
        val again = LibraryDerive.movies(list, lang, PreviousTitles(list, lang, first.lang))
        assertEquals(list.size, again.reused)
        assertEquals(first.lang, again.lang)
    }
}
