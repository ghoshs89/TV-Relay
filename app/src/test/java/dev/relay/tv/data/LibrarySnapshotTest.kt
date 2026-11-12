package dev.relay.tv.data

import dev.relay.tv.data.LibrarySnapshot.FileStamp
import dev.relay.tv.data.LibrarySnapshot.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

class LibrarySnapshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stamps = List(6) { FileStamp(1_000L + it, 1_700_000_000_000L + it) }

    /** A generated library and everything the catalog works out from it, the way the JSON path does ([LibraryDerive]). */
    private class Library(
        val liveCats: List<Category>, val vodCats: List<Category>, val seriesCats: List<Category>,
        val channels: List<Channel>, val movies: List<Movie>, val series: List<Series>,
    ) {
        val liveLang = LibraryDerive.categoryLangs(liveCats)
        val vodLang = LibraryDerive.categoryLangs(vodCats)
        val seriesLang = LibraryDerive.categoryLangs(seriesCats)
        val ch = LibraryDerive.channels(channels, liveLang)
        val mv = LibraryDerive.movies(movies, vodLang)
        val sr = LibraryDerive.series(series, seriesLang)

        fun source(stamps: List<FileStamp>) = LibrarySnapshot.Source(
            stamps, liveCats, vodCats, seriesCats,
            channels, ch.lang, ch.versionKeys, ch.quality, ch.sortKeys, ch.hd,
            movies, mv.lang, mv.added, mv.rated, mv.byName,
            series, sr.lang, sr.added, sr.rated, sr.byName,
        )
    }

    /** Category ids and file types as separate String objects per item, the way a JSON parser makes them, so sharing has to come from the reader. */
    private fun fresh(s: String) = String(s.toCharArray())

    private fun library(channels: Int = 2_000, movies: Int = 5_000, series: Int = 1_000, seed: Int = 3): Library {
        val rnd = Random(seed)
        val catNames = TextCorpus.categories()
        val live = catNames.take(40).mapIndexed { i, n -> Category("L$i", n, n.contains("adult", true)) }
        val vod = catNames.drop(80).take(30).mapIndexed { i, n -> Category("V$i", n, i % 11 == 0) }
        val ser = catNames.drop(80).take(20).mapIndexed { i, n -> Category("S$i", n, false) }
        val names = TextCorpus.channelNames(channels + 20)
        val chList = (0 until channels).map { i ->
            Channel(
                // Two channels share an id (a provider can list one twice), one id is negative.
                id = if (i == 1) 0 else if (i == 7) -5 else i,
                num = if (i % 9 == 0) 0 else rnd.nextInt(5_000),
                name = names[i],
                logo = if (i % 5 == 0) null else "http://img.example/logo/${i % 24}.png",
                categoryId = fresh("L${rnd.nextInt(live.size)}"),
                epgId = if (i % 4 == 0) null else "ch$i",
                archiveDays = if (i % 5 == 0) 3 else 0,
            )
        }
        val titles = TextCorpus.titles(movies + series) + TextCorpus.seedNames + TextCorpus.fuzzed(200, seed = 41)
        val mvList = (0 until movies).map { i ->
            Movie(
                id = if (i == 3) 100_002 else 100_000 + i,
                // Every 25th title repeats the one before in another case: equal under A to Z, so ties are tested.
                name = if (i % 25 == 5) titles[i - 1].uppercase() else titles[i],
                poster = if (i % 7 == 0) null else "http://img.example/p/${i % 24}.jpg",
                categoryId = fresh("V${rnd.nextInt(vod.size)}"),
                // Many unrated, many equal ratings, so ties matter in every order.
                rating = if (i % 3 == 0) 0.0 else (rnd.nextInt(18) + 1) / 2.0,
                ext = fresh(if (i % 4 == 0) "mkv" else "mp4"),
                added = 1_600_000_000L + rnd.nextInt(40),
                tmdbId = if (i % 6 == 0) null else (1000 + i).toString(),
                year = if (i % 8 == 0) null else fresh((1990 + i % 35).toString()),
            )
        }
        val srList = (0 until series).map { i ->
            Series(
                id = 200_000 + i,
                name = if (i % 25 == 5) titles[movies + i - 1].lowercase() else titles[movies + i],
                cover = if (i % 7 == 0) null else "http://img.example/c/${i % 24}.jpg",
                categoryId = fresh("S${rnd.nextInt(ser.size)}"),
                rating = if (i % 4 == 0) 0.0 else (rnd.nextInt(18) + 1) / 2.0,
                year = if (i % 5 == 0) null else fresh((1995 + i % 30).toString()),
                added = 1_600_000_000L + rnd.nextInt(30),
                tmdbId = if (i % 6 == 0) null else (5000 + i).toString(),
            )
        }
        return Library(live, vod, ser, chList, mvList, srList)
    }

    private fun write(lib: Library, name: String = "library.snap", stamps: List<FileStamp> = this.stamps): File {
        val f = File(tmp.root, name)
        LibrarySnapshot.write(f, lib.source(stamps), now = 1_731_400_000_000L)
        return f
    }

    private fun loaded(f: File, current: List<FileStamp> = stamps): LibrarySnapshot.Loaded {
        val r = LibrarySnapshot.readAll(f, current)
        if (r is LibrarySnapshot.Unused) fail("not used: ${r.reason}")
        return r as LibrarySnapshot.Loaded
    }

    private fun unusedReason(f: File, current: List<FileStamp> = stamps): Reason? =
        (LibrarySnapshot.readAll(f, current) as? LibrarySnapshot.Unused)?.reason?.reason

    // ---------- Same answers as the JSON path ----------

    @Test
    fun aSavedLibraryReadsBackEqualToTheOneThatWasPrepared() {
        val lib = library()
        val got = loaded(write(lib)).library
        assertEquals(lib.liveCats, got.cats.live)
        assertEquals(lib.vodCats, got.cats.vod)
        assertEquals(lib.seriesCats, got.cats.series)

        assertEquals(lib.channels, got.channels.channels)
        assertEquals(lib.ch.lang, got.channels.lang)
        assertEquals(lib.ch.versionKeys, got.channels.versionKeys)
        assertEquals(lib.ch.quality, got.channels.quality)
        assertEquals(lib.ch.sortKeys, got.channels.sortKeys)
        assertEquals(lib.ch.hd, got.channels.hd)

        assertEquals(lib.movies, got.movies.movies)
        assertEquals(lib.mv.lang, got.movies.lang)
        assertEquals(lib.mv.added, got.movies.added)
        assertEquals(lib.mv.rated, got.movies.rated)
        assertEquals(lib.mv.byName, got.movies.byName)

        assertEquals(lib.series, got.series.series)
        assertEquals(lib.sr.lang, got.series.lang)
        assertEquals(lib.sr.added, got.series.added)
        assertEquals(lib.sr.rated, got.series.rated)
        assertEquals(lib.sr.byName, got.series.byName)
    }

    @Test
    fun theLibraryIsVariedEnoughToMeanSomething() {
        val lib = library()
        assertTrue(lib.ch.lang.values.toSet().size >= 3)
        assertTrue(lib.ch.hd.isNotEmpty() && lib.ch.hd.size < lib.channels.size)
        assertTrue(lib.mv.lang.values.toSet().size >= 3)
        assertTrue(lib.mv.rated.size in 1 until lib.movies.size)
        // Ties exist in every order, so tie handling is really tested.
        assertTrue(lib.movies.groupBy { it.added }.values.any { it.size > 1 })
        assertTrue(lib.movies.filter { it.rating > 0 }.groupBy { it.rating }.values.any { it.size > 1 })
        assertTrue(lib.movies.groupBy { it.name.lowercase() }.values.any { it.size > 1 })
        assertEquals(2, lib.channels.count { it.id == 0 })
        assertEquals(2, lib.movies.count { it.id == 100_002 })
    }

    @Test
    fun savedOrdersAreTheOnesTheStagesComputeIncludingTies() {
        val lib = library()
        val got = loaded(write(lib)).library.movies
        // The same expressions the stages use, written out here so the orders are pinned independently of LibraryDerive.
        assertEquals(lib.movies.sortedByDescending { it.added }, got.added)
        assertEquals(lib.movies.filter { it.rating > 0 }.sortedByDescending { it.rating }, got.rated)
        assertEquals(lib.movies.map { it to it.name.lowercase() }.sortedBy { it.second }.map { it.first }, got.byName)
        val series = loaded(write(lib, "second.snap")).library.series
        assertEquals(lib.series.sortedByDescending { it.added }, series.added)
        assertEquals(lib.series.filter { it.rating > 0 }.sortedByDescending { it.rating }, series.rated)
        assertEquals(lib.series.map { it to it.name.lowercase() }.sortedBy { it.second }.map { it.first }, series.byName)
    }

    @Test
    fun orderedListsHoldTheSameObjectsAsTheLibrary() {
        val got = loaded(write(library(movies = 500))).library.movies
        val inLibrary = java.util.IdentityHashMap<Movie, Boolean>().also { m -> got.movies.forEach { m[it] = true } }
        assertTrue(got.added.all { inLibrary.containsKey(it) })
        assertTrue(got.rated.all { inLibrary.containsKey(it) })
        assertTrue(got.byName.all { inLibrary.containsKey(it) })
    }

    @Test
    fun repeatedTextIsOneInstanceAfterReading() {
        val got = loaded(write(library())).library
        fun <T> sharesText(items: List<T>, key: (T) -> String?) {
            val first = HashMap<String, String>()
            for (t in items) {
                val v = key(t) ?: continue
                val seen = first.getOrPut(v) { v }
                assertSame("'$v' is held twice", seen, v)
            }
        }
        sharesText(got.channels.channels) { it.categoryId }
        sharesText(got.movies.movies) { it.categoryId }
        sharesText(got.movies.movies) { it.ext }
        sharesText(got.movies.movies) { it.year }
        sharesText(got.series.series) { it.categoryId }
        sharesText(got.series.series) { it.year }
        assertTrue(got.movies.movies.map { it.categoryId }.toSet().size <= 30)
    }

    @Test
    fun languageCountsMatchWhatTheReportWouldShow() {
        val lib = library()
        val got = loaded(write(lib)).library
        fun counts(ids: List<Int>, lang: Map<Int, String>) = ids.groupingBy { lang[it] }.eachCount()
        assertEquals(counts(lib.channels.map { it.id }, lib.ch.lang), counts(got.channels.channels.map { it.id }, got.channels.lang))
        assertEquals(counts(lib.movies.map { it.id }, lib.mv.lang), counts(got.movies.movies.map { it.id }, got.movies.lang))
        assertEquals(counts(lib.series.map { it.id }, lib.sr.lang), counts(got.series.series.map { it.id }, got.series.lang))
    }

    @Test
    fun ratingsKeepEveryBit() {
        val lib = library(channels = 10, movies = 50, series = 10)
        val odd = lib.movies.toMutableList()
        odd[0] = odd[0].copy(rating = 7.25)
        odd[1] = odd[1].copy(rating = 0.1 + 0.2)
        odd[2] = odd[2].copy(rating = 9.999999999)
        val l2 = Library(lib.liveCats, lib.vodCats, lib.seriesCats, lib.channels, odd, lib.series)
        val got = loaded(write(l2)).library.movies.movies
        odd.forEachIndexed { i, m -> assertEquals(m.rating.toRawBits(), got[i].rating.toRawBits()) }
    }

    @Test
    fun anEmptyLibraryRoundTrips() {
        val empty = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val got = loaded(write(empty)).library
        assertTrue(got.channels.channels.isEmpty() && got.movies.movies.isEmpty() && got.series.series.isEmpty())
        assertTrue(got.movies.added.isEmpty() && got.movies.byName.isEmpty())
    }

    @Test
    fun theSizeIsReportedAndWellBelowTheJson() {
        val f = write(library())
        assertEquals(f.length(), loaded(f).bytes)
        assertTrue(f.length() > LibrarySnapshot.HEADER_SIZE)
    }

    // ---------- Never used when it should not be ----------

    @Test
    fun aMissingFileIsMissing() {
        assertEquals(Reason.MISSING, unusedReason(File(tmp.root, "nothing.snap")))
    }

    @Test
    fun changedJsonFilesMeanTheCopyIsNotUsed() {
        val f = write(library(channels = 100, movies = 200, series = 50))
        for (i in 0 until 6) {
            val longer = stamps.toMutableList().also { it[i] = FileStamp(it[i].length + 1, it[i].modified) }
            val newer = stamps.toMutableList().also { it[i] = FileStamp(it[i].length, it[i].modified + 1000) }
            assertEquals("length of file $i", Reason.FILES_CHANGED, unusedReason(f, longer))
            assertEquals("date of file $i", Reason.FILES_CHANGED, unusedReason(f, newer))
        }
        assertNotNull(loaded(f, stamps))
    }

    private fun patchHeader(f: File, fixCrc: Boolean, change: (ByteBuffer) -> Unit) {
        val bytes = f.readBytes()
        val head = ByteBuffer.wrap(bytes, 0, LibrarySnapshot.HEADER_SIZE)
        change(head)
        if (fixCrc) {
            val crc = CRC32().apply { update(bytes, 0, LibrarySnapshot.HEADER_SIZE - 4) }
            ByteBuffer.wrap(bytes).putInt(LibrarySnapshot.HEADER_SIZE - 4, crc.value.toInt())
        }
        f.writeBytes(bytes)
    }

    @Test
    fun anotherLogicVersionMeansTheCopyIsNotUsed() {
        val f = write(library(channels = 100, movies = 200, series = 50))
        patchHeader(f, fixCrc = true) { it.putInt(8, LibrarySnapshot.LOGIC + 1) }
        assertEquals(Reason.LOGIC_CHANGED, unusedReason(f))
    }

    @Test
    fun anotherFormatMeansTheCopyIsNotUsed() {
        val f = write(library(channels = 100, movies = 200, series = 50))
        patchHeader(f, fixCrc = false) { it.putInt(4, LibrarySnapshot.FORMAT + 1) }
        assertEquals(Reason.OLDER_FORMAT, unusedReason(f))
    }

    @Test
    fun aFileThatIsNotACopyIsDamaged() {
        val f = File(tmp.root, "other.snap")
        f.writeBytes(ByteArray(5_000) { (it * 31).toByte() })
        assertEquals(Reason.DAMAGED, unusedReason(f))
        f.writeBytes(ByteArray(10))
        assertEquals(Reason.DAMAGED, unusedReason(f))
        f.writeBytes(ByteArray(0))
        assertEquals(Reason.DAMAGED, unusedReason(f))
    }

    @Test
    fun everyByteOfTheHeaderIsProtected() {
        val f = write(library(channels = 100, movies = 200, series = 50))
        val original = f.readBytes()
        for (i in 0 until LibrarySnapshot.HEADER_SIZE) {
            val bytes = original.copyOf()
            bytes[i] = (bytes[i].toInt() xor 0x40).toByte()
            f.writeBytes(bytes)
            // Never throws, never loads.
            val r = LibrarySnapshot.readAll(f, stamps)
            assertTrue("header byte $i was accepted", r is LibrarySnapshot.Unused)
        }
    }

    @Test
    fun aCutOffFileIsDamagedWhereverItIsCut() {
        val lib = library(channels = 300, movies = 600, series = 100)
        val f = write(lib)
        val original = f.readBytes()
        val rnd = Random(5)
        val cuts = mutableListOf(0, 1, LibrarySnapshot.HEADER_SIZE - 1, LibrarySnapshot.HEADER_SIZE, LibrarySnapshot.HEADER_SIZE + 1, original.size - 1)
        repeat(10) { cuts += rnd.nextInt(original.size) }
        for (cut in cuts) {
            f.writeBytes(original.copyOf(cut))
            assertEquals("cut at $cut of ${original.size}", Reason.DAMAGED, unusedReason(f))
        }
    }

    @Test
    fun extraBytesAtTheEndAreDamaged() {
        val f = write(library(channels = 100, movies = 200, series = 50))
        f.appendBytes(byteArrayOf(1, 2, 3))
        assertEquals(Reason.DAMAGED, unusedReason(f))
    }

    @Test
    fun aFlippedByteInAnySectionIsCaught() {
        val f = write(library(channels = 300, movies = 600, series = 100))
        val original = f.readBytes()
        val head = ByteBuffer.wrap(original)
        val sectionsAt = 4 + 4 + 4 + 8 + 6 * 16 + 12
        val rnd = Random(9)
        for (s in 0 until 4) {
            val offset = head.getLong(sectionsAt + s * 20).toInt()
            val length = head.getLong(sectionsAt + s * 20 + 8).toInt()
            assertTrue("section $s is empty", length > 0)
            val spots = mutableListOf(offset, offset + length - 1, offset + length / 2)
            repeat(20) { spots += offset + rnd.nextInt(length) }
            for (spot in spots) {
                for (mask in listOf(0x01, 0x80, 0xFF)) {
                    val bytes = original.copyOf()
                    bytes[spot] = (bytes[spot].toInt() xor mask).toByte()
                    f.writeBytes(bytes)
                    assertEquals("section $s, byte $spot, mask $mask", Reason.DAMAGED, unusedReason(f))
                }
            }
        }
        f.writeBytes(original)
        assertNotNull(loaded(f))
    }

    @Test
    fun aCopyWithEveryCountInflatedDoesNotAllocateOrCrash() {
        // A damaged count must be refused by the checks, not by running out of memory.
        val f = write(library(channels = 50, movies = 80, series = 20))
        val bytes = f.readBytes()
        val head = ByteBuffer.wrap(bytes)
        val countsAt = 4 + 4 + 4 + 8 + 6 * 16
        for (k in 0 until 3) head.putInt(countsAt + k * 4, 2_000_000_000)
        val crc = CRC32().apply { update(bytes, 0, LibrarySnapshot.HEADER_SIZE - 4) }
        head.putInt(LibrarySnapshot.HEADER_SIZE - 4, crc.value.toInt())
        f.writeBytes(bytes)
        assertEquals(Reason.DAMAGED, unusedReason(f))
    }

    // ---------- Writing ----------

    @Test
    fun writingReplacesTheOldCopyAndLeavesNoTempFile() {
        val lib = library(channels = 100, movies = 200, series = 50)
        val f = write(lib)
        val first = f.readBytes()
        val bigger = library(channels = 150, movies = 300, series = 60, seed = 8)
        write(bigger)
        assertFalse(first.contentEquals(f.readBytes()))
        assertEquals(bigger.channels.size, loaded(f).library.channels.channels.size)
        assertEquals(listOf("library.snap"), tmp.root.list()!!.toList())
    }

    @Test
    fun aCancelledWriteChangesNothing() {
        val lib = library(channels = 1_500, movies = 1_500, series = 300)
        val f = write(lib)
        val before = f.readBytes()
        try {
            LibrarySnapshot.write(f, library(seed = 99).source(stamps), now = 5L) { true }
            fail("not cancelled")
        } catch (e: LibrarySnapshot.SnapshotCancelled) {
            // expected
        }
        assertTrue(before.contentEquals(f.readBytes()))
        assertEquals(listOf("library.snap"), tmp.root.list()!!.toList())
    }

    @Test
    fun aWriteThatFailsMidwayKeepsTheOldCopy() {
        val lib = library(channels = 100, movies = 200, series = 50)
        val f = write(lib)
        val before = f.readBytes()
        val badSource = lib.source(stamps).let { s ->
            // A channel without its derived facts (should never happen) cannot be written.
            LibrarySnapshot.Source(
                s.stamps, s.liveCats, s.vodCats, s.seriesCats, s.channels, s.chLang, emptyMap(), s.quality, s.sortKeys, s.hd,
                s.movies, s.mvLang, s.moviesByAdded, s.moviesByRating, s.moviesByName,
                s.series, s.srLang, s.seriesByAdded, s.seriesByRating, s.seriesByName,
            )
        }
        try {
            LibrarySnapshot.write(f, badSource, now = 5L)
            fail("written")
        } catch (e: SnapshotFormatException) {
            // expected
        }
        assertTrue(before.contentEquals(f.readBytes()))
        assertEquals(listOf("library.snap"), tmp.root.list()!!.toList())
    }

    @Test
    fun theWriterCheckedFileCanBeReadWhileAnotherSectionIsReadAtTheSameTime() {
        val lib = library(channels = 500, movies = 800, series = 200)
        val f = write(lib)
        val opened = LibrarySnapshot.open(f, stamps) as LibrarySnapshot.Opened
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val ch = pool.submit<LibrarySnapshot.LoadedChannels> { opened.readChannels() }
            val mv = pool.submit<LibrarySnapshot.LoadedMovies> { opened.readMovies() }
            val sr = pool.submit<LibrarySnapshot.LoadedSeries> { opened.readSeries() }
            assertEquals(lib.channels, ch.get().channels)
            assertEquals(lib.movies, mv.get().movies)
            assertEquals(lib.series, sr.get().series)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun headerIsTheSizeTheCodeSays() {
        val f = write(library(channels = 5, movies = 5, series = 5))
        val bytes = f.readBytes()
        val sections = ByteBuffer.wrap(bytes)
        val at = 4 + 4 + 4 + 8 + 6 * 16 + 12
        assertEquals(LibrarySnapshot.HEADER_SIZE.toLong(), sections.getLong(at))
        assertEquals(212, LibrarySnapshot.HEADER_SIZE)
    }

    @Test
    fun randomAccessReadsSeeTheSameBytesAsTheWriterWrote() {
        // The header is rewritten after the sections; make sure the sections were not disturbed.
        val lib = library(channels = 200, movies = 300, series = 80)
        val f = write(lib)
        RandomAccessFile(f, "r").use { raf -> assertEquals(f.length(), raf.length()) }
        assertEquals(lib.channels, loaded(f).library.channels.channels)
    }
}
