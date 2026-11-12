package dev.relay.tv.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.zip.CRC32

/**
 * A ready-to-use copy of the prepared library, so a restart reads it in seconds instead of parsing
 * about 100 MB of JSON and working out languages and sort keys again (see docs/PLAN-4.md).
 *
 * One file, `library.snap`, next to the provider's JSON lists. A fixed-size header says which
 * version of Relay wrote it, which six JSON files (length and date) it was made from, and where
 * each of the four sections starts, how long it is and its CRC32. The sections are categories,
 * channels, movies and series; channels, movies and series can be read at the same time.
 *
 * The copy is only ever a shortcut. On any doubt (format, [LOGIC], the JSON files, a checksum, a
 * read error) it is ignored, and the lists are read from the JSON as before.
 *
 * Not in the copy: renames and channel groups (applied after loading), learned title languages,
 * anything per profile, the guide.
 */
object LibrarySnapshot {
    /** Layout of the file. Bump when the encoding below changes. */
    const val FORMAT = 1

    /**
     * Version of everything the copy stores that Relay worked out: languages, version keys,
     * quality, sort keys, HD flags, category adult flags and the three title orders. Bump it
     * whenever those rules change, so an update never shows values the old rules made.
     * SnapshotLogicTest fails when the text rules change without a bump. Parser defaults (what a
     * missing name or file type becomes) are not covered by that test: bump this when they change.
     */
    const val LOGIC = 1

    const val FILE_NAME = "library.snap"
    private const val MAGIC = 0x524C594C // "RLYL"
    private const val FILES = 6
    private const val SECTIONS = 4
    internal const val HEADER_SIZE = 4 + 4 + 4 + 8 + FILES * 16 + 3 * 4 + SECTIONS * 20 + 4

    private const val CATS = 0
    private const val CHANNELS = 1
    private const val MOVIES = 2
    private const val SERIES = 3

    /** Length and last-modified time of one of the provider's JSON files. */
    data class FileStamp(val length: Long, val modified: Long)

    enum class Reason(val text: String) {
        MISSING("missing"),
        OLDER_FORMAT("older format"),
        LOGIC_CHANGED("detection logic changed"),
        FILES_CHANGED("library files changed"),
        DAMAGED("damaged"),
    }

    sealed interface Opening

    class NotUsed(val reason: Reason, val detail: String? = null) : Opening {
        override fun toString() = reason.text + (detail?.let { " ($it)" } ?: "")
    }

    /** Thrown inside [write] when the caller says the copy is no longer wanted. */
    class SnapshotCancelled : RuntimeException("cancelled")

    class Section(val offset: Long, val length: Long, val crc: Int)

    class Header(
        val writtenAt: Long,
        val stamps: List<FileStamp>,
        val channelCount: Int,
        val movieCount: Int,
        val seriesCount: Int,
        val sections: List<Section>,
    )

    class LoadedCategories(val live: List<Category>, val vod: List<Category>, val series: List<Category>)

    class LoadedChannels(
        val channels: List<Channel>,
        val lang: HashMap<Int, String>,
        val versionKeys: HashMap<Int, String>,
        val quality: HashMap<Int, Int>,
        val sortKeys: HashMap<Int, String>,
        val hd: HashSet<Int>,
    )

    class LoadedMovies(
        val movies: List<Movie>,
        val lang: HashMap<Int, String>,
        val added: List<Movie>,
        val rated: List<Movie>,
        val byName: List<Movie>,
    )

    class LoadedSeries(
        val series: List<Series>,
        val lang: HashMap<Int, String>,
        val added: List<Series>,
        val rated: List<Series>,
        val byName: List<Series>,
    )

    class LoadedLibrary(
        val cats: LoadedCategories,
        val channels: LoadedChannels,
        val movies: LoadedMovies,
        val series: LoadedSeries,
    )

    /** Everything [write] saves: references to the lists and maps the catalog has installed, never copies. */
    class Source(
        val stamps: List<FileStamp>,
        val liveCats: List<Category>,
        val vodCats: List<Category>,
        val seriesCats: List<Category>,
        val channels: List<Channel>,
        val chLang: Map<Int, String>,
        val versionKeys: Map<Int, String>,
        val quality: Map<Int, Int>,
        val sortKeys: Map<Int, String>,
        val hd: Set<Int>,
        val movies: List<Movie>,
        val mvLang: Map<Int, String>,
        val moviesByAdded: List<Movie>,
        val moviesByRating: List<Movie>,
        val moviesByName: List<Movie>,
        val series: List<Series>,
        val srLang: Map<Int, String>,
        val seriesByAdded: List<Series>,
        val seriesByRating: List<Series>,
        val seriesByName: List<Series>,
    )

    // ---------- Opening ----------

    /**
     * Checks the header of [file] against the version of this code and the JSON files as they are
     * now ([current], in the order of [CatalogParser.ACTIONS]). Reads nothing else.
     */
    fun open(file: File, current: List<FileStamp>): Opening {
        if (!file.isFile) return NotUsed(Reason.MISSING)
        val size = file.length()
        if (size < HEADER_SIZE) return NotUsed(Reason.DAMAGED, "shorter than its header")
        val bytes = ByteArray(HEADER_SIZE)
        try {
            FileInputStream(file).use { input ->
                var got = 0
                while (got < bytes.size) {
                    val n = input.read(bytes, got, bytes.size - got)
                    if (n < 0) return NotUsed(Reason.DAMAGED, "header cut short")
                    got += n
                }
            }
        } catch (e: IOException) {
            return NotUsed(Reason.DAMAGED, e.message)
        }
        val b = ByteBuffer.wrap(bytes)
        if (b.getInt() != MAGIC) return NotUsed(Reason.DAMAGED, "not a library copy")
        if (b.getInt() != FORMAT) return NotUsed(Reason.OLDER_FORMAT)
        val crc = CRC32().apply { update(bytes, 0, HEADER_SIZE - 4) }
        if (crc.value.toInt() != b.getInt(HEADER_SIZE - 4)) return NotUsed(Reason.DAMAGED, "header checksum")
        if (b.getInt() != LOGIC) return NotUsed(Reason.LOGIC_CHANGED)
        val writtenAt = b.getLong()
        val stamps = List(FILES) { FileStamp(b.getLong(), b.getLong()) }
        if (stamps != current) return NotUsed(Reason.FILES_CHANGED)
        val channelCount = b.getInt()
        val movieCount = b.getInt()
        val seriesCount = b.getInt()
        if (channelCount < 0 || movieCount < 0 || seriesCount < 0) return NotUsed(Reason.DAMAGED, "negative count")
        val sections = List(SECTIONS) { Section(b.getLong(), b.getLong(), b.getInt()) }
        var expectedStart = HEADER_SIZE.toLong()
        for (s in sections) {
            if (s.offset != expectedStart || s.length < 0) return NotUsed(Reason.DAMAGED, "sections out of place")
            expectedStart += s.length
        }
        if (expectedStart != size) return NotUsed(Reason.DAMAGED, "file is $size bytes, header says $expectedStart")
        return Opened(file, Header(writtenAt, stamps, channelCount, movieCount, seriesCount, sections))
    }

    /** A copy whose header matched. Each `read…` opens its own stream on the file, so channels, movies and series can be read at the same time. */
    class Opened internal constructor(private val file: File, val header: Header) : Opening {
        val sizeBytes: Long get() = header.sections.sumOf { it.length } + HEADER_SIZE

        private fun <T> section(index: Int, read: (SnapshotReader) -> T): T {
            val s = header.sections[index]
            FileInputStream(file).use { input ->
                input.channel.position(s.offset)
                val r = SnapshotReader(input, s.length)
                val value = read(r)
                if (r.finish().toInt() != s.crc) throw SnapshotFormatException("checksum mismatch in section $index")
                return value
            }
        }

        /** The item count a section starts with must be the one in the header, and cannot be more than the section has bytes. */
        private fun count(r: SnapshotReader, expected: Int, index: Int): Int {
            val n = r.readVarInt()
            if (n != expected || n < 0 || n.toLong() > header.sections[index].length) {
                throw SnapshotFormatException("item count $n, header says $expected")
            }
            return n
        }

        fun readCategories(): LoadedCategories = section(CATS) { r ->
            fun list(): List<Category> {
                val n = r.readBounded(r.left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                val out = ArrayList<Category>(n)
                repeat(n) {
                    val id = r.readText()
                    val name = r.readText()
                    val adult = r.readByte()
                    if (adult > 1) throw SnapshotFormatException("bad flag")
                    out.add(Category(id, name, adult == 1))
                }
                return out
            }
            LoadedCategories(list(), list(), list())
        }

        fun readChannels(): LoadedChannels = section(CHANNELS) { r ->
            val n = count(r, header.channelCount, CHANNELS)
            val cat = SharedReader()
            val lang = SharedReader()
            val list = ArrayList<Channel>(n)
            val chLang = HashMap<Int, String>()
            val keys = HashMap<Int, String>(n * 2)
            val quality = HashMap<Int, Int>(n * 2)
            val sortKeys = HashMap<Int, String>(n * 2)
            val hd = HashSet<Int>()
            repeat(n) {
                val id = r.readZigZagInt()
                val num = r.readZigZagInt()
                val name = r.readText()
                val logo = r.readString()
                val categoryId = cat.readText(r)
                val epgId = r.readString()
                val archiveDays = r.readZigZagInt()
                val l = lang.read(r)
                val key = r.readText()
                val q = r.readZigZagInt()
                val sortKey = r.readText()
                val flag = r.readByte()
                if (flag > 1) throw SnapshotFormatException("bad flag")
                list.add(Channel(id, num, name, logo, categoryId, epgId, archiveDays))
                if (l != null) chLang[id] = l
                keys[id] = key
                quality[id] = q
                sortKeys[id] = sortKey
                if (flag == 1) hd.add(id)
            }
            LoadedChannels(list, chLang, keys, quality, sortKeys, hd)
        }

        fun readMovies(): LoadedMovies = section(MOVIES) { r ->
            val n = count(r, header.movieCount, MOVIES)
            val cat = SharedReader()
            val ext = SharedReader()
            val year = SharedReader()
            val lang = SharedReader()
            val list = ArrayList<Movie>(n)
            val mvLang = HashMap<Int, String>()
            repeat(n) {
                val id = r.readZigZagInt()
                val name = r.readText()
                val poster = r.readString()
                val categoryId = cat.readText(r)
                val rating = r.readDouble()
                val e = ext.readText(r)
                val added = r.readZigZagLong()
                val tmdb = r.readString()
                val y = year.read(r)
                val l = lang.read(r)
                list.add(Movie(id, name, poster, categoryId, rating, e, added, tmdb, y))
                if (l != null) mvLang[id] = l
            }
            LoadedMovies(list, mvLang, readOrder(r, list), readOrder(r, list), readOrder(r, list))
        }

        fun readSeries(): LoadedSeries = section(SERIES) { r ->
            val n = count(r, header.seriesCount, SERIES)
            val cat = SharedReader()
            val year = SharedReader()
            val lang = SharedReader()
            val list = ArrayList<Series>(n)
            val srLang = HashMap<Int, String>()
            repeat(n) {
                val id = r.readZigZagInt()
                val name = r.readText()
                val cover = r.readString()
                val categoryId = cat.readText(r)
                val rating = r.readDouble()
                val y = year.read(r)
                val added = r.readZigZagLong()
                val tmdb = r.readString()
                val l = lang.read(r)
                list.add(Series(id, name, cover, categoryId, rating, y, added, tmdb))
                if (l != null) srLang[id] = l
            }
            LoadedSeries(list, srLang, readOrder(r, list), readOrder(r, list), readOrder(r, list))
        }

        /** Reads everything, one section after another; throws [SnapshotFormatException] or [IOException] if any part is bad. */
        fun readAll(): LoadedLibrary = LoadedLibrary(readCategories(), readChannels(), readMovies(), readSeries())
    }

    sealed interface ReadResult
    class Loaded(val library: LoadedLibrary, val bytes: Long, val writtenAt: Long) : ReadResult
    class Unused(val reason: NotUsed) : ReadResult

    /** Opens and reads the whole copy; any failure comes back as [Unused] with the reason, never as an exception. */
    fun readAll(file: File, current: List<FileStamp>): ReadResult =
        when (val o = open(file, current)) {
            is NotUsed -> Unused(o)
            is Opened -> try {
                Loaded(o.readAll(), o.sizeBytes, o.header.writtenAt)
            } catch (e: IOException) {
                Unused(NotUsed(Reason.DAMAGED, e.message))
            } catch (e: RuntimeException) {
                Unused(NotUsed(Reason.DAMAGED, e.message ?: e.javaClass.simpleName))
            }
        }

    private fun <T> readOrder(r: SnapshotReader, items: List<T>): List<T> {
        val n = r.readBounded(items.size)
        val out = ArrayList<T>(n)
        repeat(n) {
            val i = r.readVarInt()
            if (i < 0 || i >= items.size) throw SnapshotFormatException("order points outside the list")
            out.add(items[i])
        }
        return out
    }

    // ---------- Writing ----------

    /**
     * Writes [source] to [target] through a temporary file in the same folder, flushes it to disk
     * and renames it into place, so a reader never sees half a copy. Walks the lists already in
     * memory; the only extra memory is the index that turns each title order into positions.
     * [cancelled] is asked every few hundred items and at the end; a true throws [SnapshotCancelled].
     * Returns the size of the finished file.
     */
    fun write(target: File, source: Source, now: Long, cancelled: () -> Boolean = { false }): Long {
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.delete()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(ByteArray(HEADER_SIZE))
                val sections = ArrayList<Section>(SECTIONS)
                var offset = HEADER_SIZE.toLong()
                fun section(body: (SnapshotWriter) -> Unit) {
                    val w = SnapshotWriter(out)
                    body(w)
                    val crc = w.finish()
                    sections += Section(offset, w.bytesWritten, crc.toInt())
                    offset += w.bytesWritten
                }
                section { writeCategories(it, source) }
                section { writeChannels(it, source, cancelled) }
                section { writeMovies(it, source, cancelled) }
                section { writeSeries(it, source, cancelled) }
                val header = encodeHeader(
                    now, source.stamps, source.channels.size, source.movies.size, source.series.size, sections,
                )
                out.flush()
                out.channel.position(0)
                out.write(header)
                out.flush()
                out.fd.sync()
            }
            if (cancelled()) throw SnapshotCancelled()
            if (!tmp.renameTo(target)) throw IOException("Couldn't move the new copy into place")
            return target.length()
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    internal fun encodeHeader(
        writtenAt: Long,
        stamps: List<FileStamp>,
        channels: Int,
        movies: Int,
        series: Int,
        sections: List<Section>,
    ): ByteArray {
        require(stamps.size == FILES && sections.size == SECTIONS)
        val b = ByteBuffer.allocate(HEADER_SIZE)
        b.putInt(MAGIC).putInt(FORMAT).putInt(LOGIC).putLong(writtenAt)
        stamps.forEach { b.putLong(it.length).putLong(it.modified) }
        b.putInt(channels).putInt(movies).putInt(series)
        sections.forEach { b.putLong(it.offset).putLong(it.length).putInt(it.crc) }
        val crc = CRC32().apply { update(b.array(), 0, HEADER_SIZE - 4) }
        b.putInt(crc.value.toInt())
        return b.array()
    }

    private fun checkCancelled(i: Int, cancelled: () -> Boolean) {
        if (i and 511 == 0 && cancelled()) throw SnapshotCancelled()
    }

    private fun writeCategories(w: SnapshotWriter, s: Source) {
        for (list in listOf(s.liveCats, s.vodCats, s.seriesCats)) {
            w.writeVarInt(list.size)
            for (c in list) {
                w.writeString(c.id)
                w.writeString(c.name)
                w.writeByte(if (c.adult) 1 else 0)
            }
        }
    }

    private fun writeChannels(w: SnapshotWriter, s: Source, cancelled: () -> Boolean) {
        w.writeVarInt(s.channels.size)
        val cat = SharedWriter()
        val lang = SharedWriter()
        var i = 0
        for (c in s.channels) {
            checkCancelled(i++, cancelled)
            w.writeZigZagInt(c.id)
            w.writeZigZagInt(c.num)
            w.writeString(c.name)
            w.writeString(c.logo)
            cat.write(w, c.categoryId)
            w.writeString(c.epgId)
            w.writeZigZagInt(c.archiveDays)
            lang.write(w, s.chLang[c.id])
            w.writeString(s.versionKeys[c.id] ?: throw SnapshotFormatException("no version key for channel ${c.id}"))
            w.writeZigZagInt(s.quality[c.id] ?: throw SnapshotFormatException("no quality for channel ${c.id}"))
            w.writeString(s.sortKeys[c.id] ?: throw SnapshotFormatException("no sort key for channel ${c.id}"))
            w.writeByte(if (c.id in s.hd) 1 else 0)
        }
    }

    private fun <T> positions(all: List<T>): IdentityHashMap<T, Int> =
        IdentityHashMap<T, Int>(all.size * 2).also { m -> all.forEachIndexed { i, t -> m[t] = i } }

    private fun <T> writeOrder(w: SnapshotWriter, order: List<T>, at: IdentityHashMap<T, Int>) {
        w.writeVarInt(order.size)
        for (t in order) w.writeVarInt(at[t] ?: throw SnapshotFormatException("an ordered title is not in the library"))
    }

    private fun writeMovies(w: SnapshotWriter, s: Source, cancelled: () -> Boolean) {
        w.writeVarInt(s.movies.size)
        val cat = SharedWriter()
        val ext = SharedWriter()
        val year = SharedWriter()
        val lang = SharedWriter()
        var i = 0
        for (m in s.movies) {
            checkCancelled(i++, cancelled)
            w.writeZigZagInt(m.id)
            w.writeString(m.name)
            w.writeString(m.poster)
            cat.write(w, m.categoryId)
            w.writeDouble(m.rating)
            ext.write(w, m.ext)
            w.writeZigZagLong(m.added)
            w.writeString(m.tmdbId)
            year.write(w, m.year)
            lang.write(w, s.mvLang[m.id])
        }
        val at = positions(s.movies)
        writeOrder(w, s.moviesByAdded, at)
        writeOrder(w, s.moviesByRating, at)
        writeOrder(w, s.moviesByName, at)
    }

    private fun writeSeries(w: SnapshotWriter, s: Source, cancelled: () -> Boolean) {
        w.writeVarInt(s.series.size)
        val cat = SharedWriter()
        val year = SharedWriter()
        val lang = SharedWriter()
        var i = 0
        for (v in s.series) {
            checkCancelled(i++, cancelled)
            w.writeZigZagInt(v.id)
            w.writeString(v.name)
            w.writeString(v.cover)
            cat.write(w, v.categoryId)
            w.writeDouble(v.rating)
            year.write(w, v.year)
            w.writeZigZagLong(v.added)
            w.writeString(v.tmdbId)
            lang.write(w, s.srLang[v.id])
        }
        val at = positions(s.series)
        writeOrder(w, s.seriesByAdded, at)
        writeOrder(w, s.seriesByRating, at)
        writeOrder(w, s.seriesByName, at)
    }
}
