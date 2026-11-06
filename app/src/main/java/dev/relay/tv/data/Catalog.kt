package dev.relay.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the provider's full library. Lists are cached on disk and parsed with a streaming
 * decoder so 50k+ VOD playlists don't blow the TV's memory budget.
 */
class Catalog(private val ctx: Context, private val api: XtreamApi) {

    var liveCats by mutableStateOf<List<Category>>(emptyList()); private set
    var vodCats by mutableStateOf<List<Category>>(emptyList()); private set
    var seriesCats by mutableStateOf<List<Category>>(emptyList()); private set

    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var movies by mutableStateOf<List<Movie>>(emptyList()); private set
    var series by mutableStateOf<List<Series>>(emptyList()); private set

    var channelsByCat by mutableStateOf<Map<String, List<Channel>>>(emptyMap()); private set
    var moviesByCat by mutableStateOf<Map<String, List<Movie>>>(emptyMap()); private set
    var seriesByCat by mutableStateOf<Map<String, List<Series>>>(emptyMap()); private set

    var moviesByAdded by mutableStateOf<List<Movie>>(emptyList()); private set
    var moviesByRating by mutableStateOf<List<Movie>>(emptyList()); private set
    var seriesByAdded by mutableStateOf<List<Series>>(emptyList()); private set
    var seriesByRating by mutableStateOf<List<Series>>(emptyList()); private set

    var adultCats by mutableStateOf<Set<String>>(emptySet()); private set

    var channelById: Map<Int, Channel> = emptyMap(); private set
    var movieById: Map<Int, Movie> = emptyMap(); private set
    var seriesById: Map<Int, Series> = emptyMap(); private set

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var updatedAt by mutableLongStateOf(0L); private set
    var epgVersion by mutableIntStateOf(0); private set

    private val epgCache = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()
    private val mutex = Mutex()
    private val dir: File get() = File(ctx.filesDir, "catalog").also { it.mkdirs() }

    private val actions = listOf(
        "get_live_categories", "get_vod_categories", "get_series_categories",
        "get_live_streams", "get_vod_streams", "get_series",
    )

    private fun file(action: String) = File(dir, "$action.json")
    private fun cacheComplete() = actions.all { file(it).exists() && file(it).length() > 0 }
    private fun cacheAgeMs() = System.currentTimeMillis() - file("get_live_streams").lastModified()
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    fun allowed(categoryId: String, restrict: Boolean) = !restrict || categoryId !in adultCats

    suspend fun load(force: Boolean = false) {
        mutex.withLock {
            loading = true
            error = null
            val started = System.currentTimeMillis()
            try {
                if (isEmpty && cacheComplete()) parse()
                if (force || !cacheComplete() || cacheAgeMs() > REFRESH_MS) {
                    try {
                        coroutineScope {
                            actions.map { a -> async { api.download(a, file(a)) } }.awaitAll()
                        }
                        parse()
                        AppLog.i(
                            "Catalog",
                            "Refreshed in ${(System.currentTimeMillis() - started) / 1000}s: ${channels.size} channels, " +
                                "${movies.size} movies, ${series.size} series",
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e("Catalog", "Refresh failed", e)
                        if (isEmpty) throw e
                        error = "Couldn't refresh (${e.message}). Showing your saved lists."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Catalog", "Load failed", e)
                error = e.message ?: "Couldn't load your playlist"
            } finally {
                loading = false
            }
            Unit
        }
    }

    fun clear() {
        liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
        channels = emptyList(); movies = emptyList(); series = emptyList()
        channelsByCat = emptyMap(); moviesByCat = emptyMap(); seriesByCat = emptyMap()
        moviesByAdded = emptyList(); moviesByRating = emptyList()
        seriesByAdded = emptyList(); seriesByRating = emptyList()
        adultCats = emptySet()
        channelById = emptyMap(); movieById = emptyMap(); seriesById = emptyMap()
        epgCache.clear()
        error = null
        dir.deleteRecursively()
    }

    private class Parsed(
        val lc: List<Category>, val vc: List<Category>, val sc: List<Category>,
        val ch: List<Channel>, val mv: List<Movie>, val sr: List<Series>,
        val mvAdded: List<Movie>, val mvRated: List<Movie>,
        val srAdded: List<Series>, val srRated: List<Series>,
        val chByCat: Map<String, List<Channel>>,
        val mvByCat: Map<String, List<Movie>>,
        val srByCat: Map<String, List<Series>>,
    )

    private suspend fun parse() {
        val p = withContext(Dispatchers.Default) {
            val lc = parseCats(file("get_live_categories"))
            val vc = parseCats(file("get_vod_categories"))
            val sc = parseCats(file("get_series_categories"))
            val ch = parseList(file("get_live_streams"), LiveDto.serializer()) { d ->
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
            }
            val mv = parseList(file("get_vod_streams"), VodDto.serializer()) { d ->
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
            }
            val sr = parseList(file("get_series"), SeriesDto.serializer()) { d ->
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
            }
            Parsed(
                lc, vc, sc, ch, mv, sr,
                mvAdded = mv.sortedByDescending { it.added },
                mvRated = mv.filter { it.rating > 0 }.sortedByDescending { it.rating },
                srAdded = sr.sortedByDescending { it.added },
                srRated = sr.filter { it.rating > 0 }.sortedByDescending { it.rating },
                chByCat = ch.groupBy { it.categoryId },
                mvByCat = mv.groupBy { it.categoryId },
                srByCat = sr.groupBy { it.categoryId },
            )
        }
        channelById = p.ch.associateBy { it.id }
        movieById = p.mv.associateBy { it.id }
        seriesById = p.sr.associateBy { it.id }
        adultCats = (p.lc + p.vc + p.sc).filter { it.adult }.map { it.id }.toSet()
        liveCats = p.lc; vodCats = p.vc; seriesCats = p.sc
        channels = p.ch; movies = p.mv; series = p.sr
        channelsByCat = p.chByCat; moviesByCat = p.mvByCat; seriesByCat = p.srByCat
        moviesByAdded = p.mvAdded; moviesByRating = p.mvRated
        seriesByAdded = p.srAdded; seriesByRating = p.srRated
        updatedAt = file("get_live_streams").lastModified()
    }

    private fun parseCats(f: File): List<Category> =
        parseList(f, CategoryDto.serializer()) { d ->
            val id = d.category_id.str() ?: return@parseList null
            val name = d.category_name.str() ?: "Category $id"
            Category(id, name, ADULT.containsMatchIn(name))
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T, R> parseList(f: File, ser: KSerializer<T>, map: (T) -> R?): List<R> {
        if (!f.exists()) return emptyList()
        return try {
            f.inputStream().buffered(256 * 1024).use { input ->
                Jsons.json.decodeToSequence(input, ser, DecodeSequenceMode.ARRAY_WRAPPED)
                    .mapNotNull { map(it) }
                    .toList()
            }
        } catch (e: Exception) {
            AppLog.e("Catalog", "Couldn't parse ${f.name}", e)
            emptyList()
        }
    }

    // ---------- Guide ----------

    suspend fun shortEpg(id: Int): List<EpgEntry> {
        val now = System.currentTimeMillis()
        epgCache[id]?.let { if (now - it.first < 10 * 60_000L) return it.second }
        val list = try {
            api.epg(id, full = false, limit = 10)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Epg", "Guide unavailable for stream $id", e)
            emptyList()
        }
        epgCache[id] = now to list
        epgVersion++
        return list
    }

    suspend fun archive(id: Int): List<EpgEntry> = try {
        api.epg(id, full = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    fun nowPlaying(id: Int): String? {
        val nowSec = System.currentTimeMillis() / 1000
        return epgCache[id]?.second?.firstOrNull { nowSec in it.start until it.end }?.title
    }

    companion object {
        private const val REFRESH_MS = 6 * 3600_000L
        private val ADULT = Regex("(?i)(adult|xxx|18\\+|porn|for adults)")
        private val YEAR = Regex("[(\\[](19|20)(\\d{2})[)\\]]")
        fun yearIn(name: String): String? = YEAR.find(name)?.let { it.groupValues[1] + it.groupValues[2] }
    }
}
