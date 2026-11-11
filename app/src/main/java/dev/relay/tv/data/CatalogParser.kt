package dev.relay.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import java.io.File
import java.io.IOException

/** A provider list that isn't a valid JSON list (error page, truncated download, wrong account). */
class InvalidListException(val list: String, cause: Throwable? = null) :
    IOException("The server sent an invalid $list list", cause)

/** Parsed library, ready to show. */
class ParsedCatalog(
    val liveCats: List<Category>,
    val vodCats: List<Category>,
    val seriesCats: List<Category>,
    val channels: List<Channel>,
    val movies: List<Movie>,
    val series: List<Series>,
    /** How long each list took to read, in ms, for the log. */
    val timings: Map<String, Long> = emptyMap(),
) {
    val counts get() = CatalogCounts(channels.size, movies.size, series.size)

    fun timingText(): String = timings.entries.joinToString(", ") { "${it.key} ${it.value} ms" }
}

data class CatalogCounts(val channels: Int, val movies: Int, val series: Int)

/**
 * Reads the provider's six list files. Streaming decode keeps 50k+ item playlists within a TV's
 * memory budget. In strict mode (used to check a fresh download before it replaces the saved
 * library) any file that isn't a complete JSON list is rejected instead of read as empty.
 */
object CatalogParser {
    val ACTIONS = listOf(
        "get_live_categories", "get_vod_categories", "get_series_categories",
        "get_live_streams", "get_vod_streams", "get_series",
    )

    private val LABELS = mapOf(
        "get_live_categories" to "live category", "get_vod_categories" to "movie category",
        "get_series_categories" to "series category", "get_live_streams" to "channel",
        "get_vod_streams" to "movie", "get_series" to "series",
    )

    /**
     * Reads the six lists one after another. [progress] gets the share of bytes read so far, 0 to 1,
     * across all six lists, and never goes backwards.
     */
    fun parse(dir: File, strict: Boolean, progress: (Float) -> Unit = {}): ParsedCatalog {
        val r = Reader(dir, strict, progress)
        val liveCats = r.categories("get_live_categories")
        val vodCats = r.categories("get_vod_categories")
        val seriesCats = r.categories("get_series_categories")
        val channels = r.channels()
        val movies = r.movies()
        val series = r.series()
        return ParsedCatalog(liveCats, vodCats, seriesCats, channels, movies, series, r.timings())
    }

    /**
     * The same result as [parse], reading the channel, movie and series lists at the same time
     * (at most [limit] at once; categories first, they are small). Strict mode rejects a bad
     * list the same way, and the first list that fails ends the others. Progress is the share of
     * all bytes read across the files.
     */
    suspend fun parseConcurrently(
        dir: File,
        strict: Boolean,
        limit: Int = Runtime.getRuntime().availableProcessors().let { if (it >= 4) 3 else 2 },
        progress: (Float) -> Unit = {},
    ): ParsedCatalog = coroutineScope {
        val r = Reader(dir, strict, progress)
        val liveCats = r.categories("get_live_categories")
        val vodCats = r.categories("get_vod_categories")
        val seriesCats = r.categories("get_series_categories")
        val gate = Semaphore(limit.coerceAtLeast(1))
        val channels = async(Dispatchers.Default) { gate.withPermit { r.channels() } }
        val movies = async(Dispatchers.Default) { gate.withPermit { r.movies() } }
        val series = async(Dispatchers.Default) { gate.withPermit { r.series() } }
        ParsedCatalog(liveCats, vodCats, seriesCats, channels.await(), movies.await(), series.await(), r.timings())
    }

    /** Shares one copy of each repeated value (category ids, file types, years) between all the titles that have it. */
    internal class Interner {
        private val pool = HashMap<String, String>()
        val size: Int get() = pool.size
        fun of(s: String?): String? {
            if (s == null) return null
            return pool[s] ?: s.also { pool[it] = it }
        }
    }

    /** Reads one library folder: knows each file's size for progress and keeps the timings. Safe to use from several threads. */
    private class Reader(private val dir: File, private val strict: Boolean, private val progress: (Float) -> Unit) {
        private fun file(a: String) = File(dir, "$a.json")
        private val total = ACTIONS.sumOf { file(it).length() }.coerceAtLeast(1L)
        private val bytesRead = LongArray(ACTIONS.size)
        private var reported = 0f
        private val lock = Any()
        private val times = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun moved(index: Int, n: Long) = synchronized(lock) {
            if (n > bytesRead[index]) bytesRead[index] = n
            val fraction = bytesRead.sum().toFloat() / total
            if (fraction > reported) {
                reported = fraction
                progress(fraction)
            }
        }

        /** Times a list and moves the byte count on, so progress is smooth even for one huge list. */
        private fun <R> timed(a: String, read: (File, (Long) -> Unit) -> R): R {
            val index = ACTIONS.indexOf(a)
            val t = System.currentTimeMillis()
            val f = file(a)
            val r = read(f) { n -> moved(index, n) }
            moved(index, f.length())
            times[LABELS[a] ?: a] = System.currentTimeMillis() - t
            return r
        }

        /** Per-list read times in the order of the files. */
        fun timings(): Map<String, Long> = LinkedHashMap<String, Long>().also { m ->
            ACTIONS.forEach { a -> val l = LABELS[a] ?: a; times[l]?.let { m[l] = it } }
        }

        fun categories(a: String): List<Category> = timed(a) { f, p -> parseCats(f, strict, p) }

        fun channels(): List<Channel> {
            val ix = Interner()
            return timed<List<Channel>>("get_live_streams") { f, p ->
                parseList(f, LiveDto.serializer(), strict, p) { d ->
                    val id = d.stream_id.int() ?: return@parseList null
                    Channel(
                        id = id,
                        num = d.num.int() ?: 0,
                        name = d.name.str() ?: "Channel $id",
                        logo = d.stream_icon.str(),
                        categoryId = ix.of(d.category_id.str()) ?: "",
                        epgId = d.epg_channel_id.str(),
                        archiveDays = if (d.tv_archive.flag()) (d.tv_archive_duration.int() ?: 1) else 0,
                    )
                }
            }.also { AppLog.d("Catalog", "Channels share ${ix.size} distinct category ids") }
        }

        fun movies(): List<Movie> {
            val ix = Interner()
            return timed<List<Movie>>("get_vod_streams") { f, p ->
                parseList(f, VodDto.serializer(), strict, p) { d ->
                    val id = d.stream_id.int() ?: return@parseList null
                    val name = d.name.str() ?: return@parseList null
                    Movie(
                        id = id,
                        name = name,
                        poster = d.stream_icon.str(),
                        categoryId = ix.of(d.category_id.str()) ?: "",
                        rating = d.rating.dbl() ?: 0.0,
                        ext = ix.of(d.container_extension.str()) ?: "mp4",
                        added = d.added.long() ?: 0L,
                        tmdbId = d.tmdb.str(),
                        year = ix.of(d.year.str() ?: yearIn(name)),
                    )
                }
            }.also { AppLog.d("Catalog", "Movies share ${ix.size} distinct category ids, file types and years") }
        }

        fun series(): List<Series> {
            val ix = Interner()
            return timed<List<Series>>("get_series") { f, p ->
                parseList(f, SeriesDto.serializer(), strict, p) { d ->
                    val id = d.series_id.int() ?: return@parseList null
                    val name = d.name.str() ?: return@parseList null
                    Series(
                        id = id,
                        name = name,
                        cover = d.cover.str(),
                        categoryId = ix.of(d.category_id.str()) ?: "",
                        rating = d.rating.dbl() ?: 0.0,
                        year = ix.of((d.year.str() ?: d.releaseDate.str() ?: d.release_date.str())?.take(4) ?: yearIn(name)),
                        added = d.last_modified.long() ?: 0L,
                        tmdbId = d.tmdb.str(),
                    )
                }
            }.also { AppLog.d("Catalog", "Series share ${ix.size} distinct category ids and years") }
        }
    }

    private fun parseCats(f: File, strict: Boolean, read: (Long) -> Unit): List<Category> =
        parseList(f, CategoryDto.serializer(), strict, read) { d ->
            val id = d.category_id.str() ?: return@parseList null
            val name = d.category_name.str() ?: "Category $id"
            Category(id, name, ADULT.containsMatchIn(name))
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T, R> parseList(f: File, ser: KSerializer<T>, strict: Boolean, read: (Long) -> Unit, map: (T) -> R?): List<R> {
        val label = LABELS[f.nameWithoutExtension] ?: f.nameWithoutExtension
        if (!f.exists()) {
            if (strict) throw InvalidListException(label)
            return emptyList()
        }
        return try {
            if (strict && !startsWithArray(f)) throw InvalidListException(label)
            CountingStream(f.inputStream(), read).buffered(256 * 1024).use { input ->
                Jsons.json.decodeToSequence(input, ser, DecodeSequenceMode.ARRAY_WRAPPED)
                    .mapNotNull { map(it) }
                    .toList()
            }
        } catch (e: InvalidListException) {
            throw e
        } catch (e: Exception) {
            if (strict) throw InvalidListException(label, e)
            AppLog.e("Catalog", "Couldn't parse ${f.name}", e)
            emptyList()
        }
    }

    /** First non-blank character is '[': rules out HTML error pages and JSON error objects. */
    private fun startsWithArray(f: File): Boolean {
        f.inputStream().buffered().use { input ->
            while (true) {
                val c = input.read()
                if (c < 0) return false
                if (c == 0xEF || c == 0xBB || c == 0xBF) continue // UTF-8 byte order mark
                if (!Character.isWhitespace(c)) return c == '['.code
            }
        }
    }

    /**
     * Moves a fully downloaded and checked set of lists over the saved ones. Each rename replaces
     * its target in one step, and nothing is touched unless every staged file is present.
     */
    fun commit(staging: File, live: File) {
        val staged = ACTIONS.map { File(staging, "$it.json") }
        if (staged.any { !it.exists() }) throw IOException("Refresh incomplete")
        live.mkdirs()
        for (f in staged) {
            if (!f.renameTo(File(live, f.name))) throw IOException("Couldn't save ${f.name}")
        }
        staging.deleteRecursively()
    }

    private val ADULT = Regex("(?i)(adult|xxx|18\\+|porn|for adults)")
    private val YEAR = Regex("[(\\[](19|20)(\\d{2})[)\\]]")
    fun yearIn(name: String): String? = YEAR.find(name)?.let { it.groupValues[1] + it.groupValues[2] }
}

/**
 * Decides whether a freshly downloaded library may replace the saved one. A provider that
 * suddenly returns nothing is almost always failing, not empty.
 */
object RefreshGuard {
    class Verdict(val accept: Boolean, val message: String?, val warnings: List<String>)

    fun check(old: CatalogCounts, new: CatalogCounts): Verdict {
        val lists = listOf(
            Triple("channels", old.channels, new.channels),
            Triple("movies", old.movies, new.movies),
            Triple("series", old.series, new.series),
        )
        val emptied = lists.filter { (_, o, n) -> o > 0 && n == 0 }.map { it.first }
        if (emptied.isNotEmpty()) {
            return Verdict(
                false,
                "Provider returned no ${emptied.joinToString(" or ")}; kept your saved library",
                emptyList(),
            )
        }
        val warnings = lists.filter { (_, o, n) -> o >= 50 && n < o / 10 }
            .map { (name, o, n) -> "$name dropped from $o to $n" }
        return Verdict(true, null, warnings)
    }
}

/** When saved data is old enough to fetch again. */
object Freshness {
    const val LIBRARY_MS = 6 * 3600_000L
    const val CHECK_EVERY_MS = 30 * 60_000L

    fun libraryDue(savedAt: Long, now: Long): Boolean = savedAt <= 0L || now - savedAt >= LIBRARY_MS
}

/** Reports how many bytes have been read, every 256 KB or so. */
private class CountingStream(input: java.io.InputStream, private val onRead: (Long) -> Unit) : java.io.FilterInputStream(input) {
    private var count = 0L
    private var lastReported = 0L

    private fun moved(n: Long) {
        if (n <= 0) return
        count += n
        if (count - lastReported >= 256 * 1024) {
            lastReported = count
            onRead(count)
        }
    }

    override fun read(): Int = super.read().also { if (it >= 0) moved(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { moved(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { moved(it) }
}
