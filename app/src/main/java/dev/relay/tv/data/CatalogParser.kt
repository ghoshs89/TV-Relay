package dev.relay.tv.data

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
) {
    val counts get() = CatalogCounts(channels.size, movies.size, series.size)
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

    fun parse(dir: File, strict: Boolean): ParsedCatalog {
        fun f(a: String) = File(dir, "$a.json")
        return ParsedCatalog(
            liveCats = parseCats(f("get_live_categories"), strict),
            vodCats = parseCats(f("get_vod_categories"), strict),
            seriesCats = parseCats(f("get_series_categories"), strict),
            channels = parseList(f("get_live_streams"), LiveDto.serializer(), strict) { d ->
                val id = d.stream_id.int() ?: return@parseList null
                Channel(
                    id = id,
                    num = d.num.int() ?: 0,
                    name = d.name.str() ?: "Channel $id",
                    logo = d.stream_icon.str(),
                    categoryId = d.category_id.str() ?: "",
                    epgId = d.epg_channel_id.str(),
                    archiveDays = if (d.tv_archive.flag()) (d.tv_archive_duration.int() ?: 1) else 0,
                )
            },
            movies = parseList(f("get_vod_streams"), VodDto.serializer(), strict) { d ->
                val id = d.stream_id.int() ?: return@parseList null
                val name = d.name.str() ?: return@parseList null
                Movie(
                    id = id,
                    name = name,
                    poster = d.stream_icon.str(),
                    categoryId = d.category_id.str() ?: "",
                    rating = d.rating.dbl() ?: 0.0,
                    ext = d.container_extension.str() ?: "mp4",
                    added = d.added.long() ?: 0L,
                    tmdbId = d.tmdb.str(),
                    year = d.year.str() ?: yearIn(name),
                )
            },
            series = parseList(f("get_series"), SeriesDto.serializer(), strict) { d ->
                val id = d.series_id.int() ?: return@parseList null
                val name = d.name.str() ?: return@parseList null
                Series(
                    id = id,
                    name = name,
                    cover = d.cover.str(),
                    categoryId = d.category_id.str() ?: "",
                    rating = d.rating.dbl() ?: 0.0,
                    plot = d.plot.str(),
                    year = (d.year.str() ?: d.releaseDate.str() ?: d.release_date.str())?.take(4) ?: yearIn(name),
                    genre = d.genre.str(),
                    added = d.last_modified.long() ?: 0L,
                    tmdbId = d.tmdb.str(),
                )
            },
        )
    }

    private fun parseCats(f: File, strict: Boolean): List<Category> =
        parseList(f, CategoryDto.serializer(), strict) { d ->
            val id = d.category_id.str() ?: return@parseList null
            val name = d.category_name.str() ?: "Category $id"
            Category(id, name, ADULT.containsMatchIn(name))
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T, R> parseList(f: File, ser: KSerializer<T>, strict: Boolean, map: (T) -> R?): List<R> {
        val label = LABELS[f.nameWithoutExtension] ?: f.nameWithoutExtension
        if (!f.exists()) {
            if (strict) throw InvalidListException(label)
            return emptyList()
        }
        return try {
            if (strict && !startsWithArray(f)) throw InvalidListException(label)
            f.inputStream().buffered(256 * 1024).use { input ->
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
