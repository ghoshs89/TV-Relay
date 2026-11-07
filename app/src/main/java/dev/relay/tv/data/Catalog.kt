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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    /** Bumped every minute while Relay is open so "now playing" text moves on as shows end. */
    var minute by mutableIntStateOf(0); private set

    fun tickMinute() {
        minute++
    }

    /** True when the saved library is over 6 hours old (or missing). */
    fun libraryDue(now: Long = System.currentTimeMillis()): Boolean =
        !cacheComplete() || Freshness.libraryDue(file("get_live_streams").lastModified(), now)

    private val epgCache = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()

    /** Channels exactly as the provider sent them; [channels] has the user's renames applied. */
    private var rawChannels: List<Channel> = emptyList()

    /** Wired by the app: user customisation, current settings and the optional external guide. */
    var custom: () -> ChannelCustom = { ChannelCustom() }
    var settings: () -> Settings = { Settings() }
    var guide: XmltvGuide? = null
    private val mutex = Mutex()
    private val dir: File get() = File(ctx.filesDir, "catalog").also { it.mkdirs() }

    private val actions = CatalogParser.ACTIONS

    private fun file(action: String) = File(dir, "$action.json")
    private fun cacheComplete() = actions.all { file(it).exists() && file(it).length() > 0 }
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    fun allowed(categoryId: String, restrict: Boolean) = !restrict || categoryId !in adultCats

    /** The single playback restriction check for a profile (see [Restrictions]). */
    fun restrictions(restrict: Boolean): Restrictions =
        if (!restrict) Restrictions.NONE
        else Restrictions(
            true, adultCats,
            channelCat = { channelById[it]?.categoryId },
            movieCat = { movieById[it]?.categoryId },
            seriesCat = { seriesById[it]?.categoryId },
        )

    suspend fun load(force: Boolean = false) {
        mutex.withLock {
            loading = true
            error = null
            val started = System.currentTimeMillis()
            try {
                if (isEmpty && cacheComplete()) applyParsed(withContext(Dispatchers.Default) { CatalogParser.parse(dir, strict = false) })
                if (force || libraryDue()) {
                    try {
                        refresh(started)
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
            refreshGuideIfNeeded(force = false)
            Unit
        }
    }

    /**
     * Downloads all six lists into a staging folder, checks every one parses as a complete list
     * and that nothing went from full to empty, and only then replaces the saved library with
     * them. A failed or suspicious refresh leaves the saved library untouched.
     */
    private suspend fun refresh(started: Long) {
        val staging = File(dir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            coroutineScope {
                actions.map { a -> async { api.download(a, File(staging, "$a.json")) } }.awaitAll()
            }
            val fresh = withContext(Dispatchers.Default) { CatalogParser.parse(staging, strict = true) }
            val old = CatalogCounts(rawChannels.size, movies.size, series.size)
            val verdict = RefreshGuard.check(old, fresh.counts)
            verdict.warnings.forEach { AppLog.w("Catalog", "Large drop after refresh: $it") }
            if (!verdict.accept) {
                AppLog.w("Catalog", "Refresh rejected: ${verdict.message} (${fresh.counts})")
                error = verdict.message
                return
            }
            withContext(Dispatchers.IO) { CatalogParser.commit(staging, dir) }
            applyParsed(fresh)
            AppLog.i(
                "Catalog",
                "Refreshed in ${(System.currentTimeMillis() - started) / 1000}s: ${channels.size} channels, " +
                    "${movies.size} movies, ${series.size} series",
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun refreshGuideIfNeeded(force: Boolean) {
        val g = guide ?: return
        val url = settings().xmltvUrl.trim()
        if (url.isEmpty() || channels.isEmpty()) return
        try {
            if (force || g.needsRefresh(url)) {
                g.refresh(url, channels)
                clearGuideCache()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Epg", "External guide refresh failed", e)
        }
    }

    fun clear() {
        liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
        channels = emptyList(); movies = emptyList(); series = emptyList()
        channelsByCat = emptyMap(); moviesByCat = emptyMap(); seriesByCat = emptyMap()
        moviesByAdded = emptyList(); moviesByRating = emptyList()
        seriesByAdded = emptyList(); seriesByRating = emptyList()
        adultCats = emptySet()
        rawChannels = emptyList()
        channelById = emptyMap(); movieById = emptyMap(); seriesById = emptyMap()
        epgCache.clear()
        error = null
        dir.deleteRecursively()
    }

    private class Indexes(p: ParsedCatalog) {
        // Language groups (see LanguageDetector), worked out once per load.
        val liveCatLang = langOf(p.liveCats)
        val vodCatLang = langOf(p.vodCats)
        val seriesCatLang = langOf(p.seriesCats)
        val chLang = HashMap<Int, String>().also { m ->
            p.channels.forEach { c -> LanguageDetector.ofChannel(c.name, liveCatLang[c.categoryId])?.let { m[c.id] = it } }
        }
        val mvLang = HashMap<Int, String>().also { m ->
            p.movies.forEach { v -> LanguageDetector.ofTitle(v.name, vodCatLang[v.categoryId])?.let { m[v.id] = it } }
        }
        val srLang = HashMap<Int, String>().also { m ->
            p.series.forEach { v -> LanguageDetector.ofTitle(v.name, seriesCatLang[v.categoryId])?.let { m[v.id] = it } }
        }
        val mvAdded = p.movies.sortedByDescending { it.added }
        val mvRated = p.movies.filter { it.rating > 0 }.sortedByDescending { it.rating }
        val srAdded = p.series.sortedByDescending { it.added }
        val srRated = p.series.filter { it.rating > 0 }.sortedByDescending { it.rating }
        val mvByCat = p.movies.groupBy { it.categoryId }
        val srByCat = p.series.groupBy { it.categoryId }
        val mvById = p.movies.associateBy { it.id }
        val srById = p.series.associateBy { it.id }
    }

    private suspend fun applyParsed(p: ParsedCatalog) {
        val t0 = System.currentTimeMillis()
        val sorted = withContext(Dispatchers.Default) { Indexes(p) }
        liveCatLang = sorted.liveCatLang; vodCatLang = sorted.vodCatLang; seriesCatLang = sorted.seriesCatLang
        chLang = sorted.chLang; mvLang = sorted.mvLang; srLang = sorted.srLang
        indexMs = System.currentTimeMillis() - t0
        langVersion++
        AppLog.i("Catalog", loadSummary().replace("\n", "; "))
        movieById = sorted.mvById
        seriesById = sorted.srById
        adultCats = (p.liveCats + p.vodCats + p.seriesCats).filter { it.adult }.map { it.id }.toSet()
        liveCats = p.liveCats; vodCats = p.vodCats; seriesCats = p.seriesCats
        movies = p.movies; series = p.series
        moviesByCat = sorted.mvByCat; seriesByCat = sorted.srByCat
        rawChannels = p.channels
        applyCustom()
        moviesByAdded = sorted.mvAdded; moviesByRating = sorted.mvRated
        seriesByAdded = sorted.srAdded; seriesByRating = sorted.srRated
        updatedAt = file("get_live_streams").lastModified()
    }

    // ---------- Languages ----------

    private var liveCatLang: Map<String, String> = emptyMap()
    private var vodCatLang: Map<String, String> = emptyMap()
    private var seriesCatLang: Map<String, String> = emptyMap()
    private var chLang: Map<Int, String> = emptyMap()
    private var mvLang: Map<Int, String> = emptyMap()
    private var srLang: Map<Int, String> = emptyMap()

    /** Original languages learned from TMDB when a title is opened; they correct the guesses. */
    private val learned = ConcurrentHashMap<String, String>()

    var langVersion by mutableIntStateOf(0); private set
    var indexMs = 0L; private set

    fun channelLang(id: Int): String? = chLang[id]
    fun movieLang(id: Int): String? = learned["m:$id"] ?: mvLang[id]
    fun seriesLang(id: Int): String? = learned["s:$id"] ?: srLang[id]
    fun liveCatLang(id: String): String? = liveCatLang[id]
    fun vodCatLang(id: String): String? = vodCatLang[id]
    fun seriesCatLang(id: String): String? = seriesCatLang[id]

    /** TMDB's original language for a title ("hi", "bn", "en", "ta"...), mapped to Relay's groups. */
    fun learnLanguage(key: String, isoCode: String?, region: String? = null) {
        val g = when (isoCode?.lowercase()) {
            "en" -> Langs.EN
            "hi" -> Langs.HI
            "bn" -> if (region.equals("BD", true)) Langs.BD else Langs.BN
            "ta", "te", "ml", "kn" -> Langs.SOUTH
            null, "" -> return
            else -> Langs.OTHER
        }
        if (learned.put(key, g) != g) langVersion++
    }

    /** Library size, how long indexing took and memory in use, for the report and the log. */
    fun loadSummary(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return "Library: ${"%,d".format(rawChannels.size)} channels, ${"%,d".format(movies.size)} movies, " +
            "${"%,d".format(series.size)} series, ${liveCats.size + vodCats.size + seriesCats.size} categories\n" +
            "Indexing took ${indexMs} ms; memory in use ${usedMb} MB of ${maxMb} MB"
    }

    fun languageReport(): LanguageReport {
        fun count(ids: Collection<Int>, lang: (Int) -> String?) = ids.groupingBy { lang(it) }.eachCount()
        val tags = HashMap<String, Int>()
        (movies.asSequence().map { it.name } + series.asSequence().map { it.name }).forEach { n ->
            val t = TitleParts.parse(n).tag
            if (t != null && LanguageDetector.ofCode(t) == null) tags[t] = (tags[t] ?: 0) + 1
        }
        val unknownCats = buildList {
            liveCats.filter { liveCatLang[it.id] == null }.forEach { add(it.name to (channelsByCat[it.id]?.size ?: 0)) }
            vodCats.filter { vodCatLang[it.id] == null }.forEach { add(it.name to (moviesByCat[it.id]?.size ?: 0)) }
            seriesCats.filter { seriesCatLang[it.id] == null }.forEach { add(it.name to (seriesByCat[it.id]?.size ?: 0)) }
        }.sortedByDescending { it.second }.take(40)
        return LanguageReport(
            channels = count(rawChannels.map { it.id }) { chLang[it] },
            movies = count(movies.map { it.id }) { movieLang(it) },
            series = count(series.map { it.id }) { seriesLang(it) },
            unknownTags = tags.entries.sortedByDescending { it.value }.take(25).map { it.key to it.value },
            unknownCategories = unknownCats,
        )
    }

    /** Re-applies channel renames after the user edits them. */
    fun applyCustom() {
        val renames = custom().renames
        val ch = if (renames.isEmpty()) rawChannels else rawChannels.map { c -> renames[c.id]?.let { c.copy(name = it) } ?: c }
        channels = ch
        channelById = ch.associateBy { it.id }
        channelsByCat = ch.groupBy { it.categoryId }
    }

    fun groupChannels(g: ChannelGroup): List<Channel> = g.channelIds.mapNotNull { channelById[it] }

    // ---------- Guide ----------

    suspend fun shortEpg(id: Int): List<EpgEntry> = shortEpgOrNull(id).orEmpty()

    /**
     * Now and next for a channel. Null when the provider failed and the external guide has nothing
     * either, so screens can offer Retry instead of claiming there's no guide. Failures aren't cached.
     */
    suspend fun shortEpgOrNull(id: Int, fresh: Boolean = false): List<EpgEntry>? {
        val now = System.currentTimeMillis()
        if (!fresh) epgCache[id]?.let { if (now - it.first < 10 * 60_000L) return it.second }
        var failed = false
        val provider = try {
            api.epg(id, full = false, limit = 10)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Epg", "Guide unavailable for stream $id", e)
            failed = true
            emptyList()
        }
        val nowSec = now / 1000
        val list = shift(provider).ifEmpty {
            // Provider has nothing for this channel: fall back to the external XMLTV guide.
            shift(guide?.entries(id).orEmpty()).filter { it.end > nowSec }.take(10)
        }
        if (failed && list.isEmpty()) return null
        epgCache[id] = now to list
        epgVersion++
        return list
    }

    suspend fun archive(id: Int): List<EpgEntry> = archiveOrNull(id).orEmpty()

    /** Catch-up listings; null when the provider couldn't be reached. */
    suspend fun archiveOrNull(id: Int): List<EpgEntry>? = try {
        shift(api.epg(id, full = true))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("Epg", "Catch-up list unavailable for stream $id", e)
        null
    }

    // Guide grid: wider window per channel, cached, with a cap on parallel requests to the provider.
    private val gridCache = ConcurrentHashMap<Int, Pair<Long, List<EpgEntry>>>()
    private val gridGate = kotlinx.coroutines.sync.Semaphore(4)

    suspend fun gridEpg(ch: Channel, from: Long, to: Long): List<EpgEntry> = gridEpgOrNull(ch, from, to).orEmpty()

    /** Null when the provider failed and there's no external guide data for the channel. */
    suspend fun gridEpgOrNull(ch: Channel, from: Long, to: Long, fresh: Boolean = false): List<EpgEntry>? {
        val now = System.currentTimeMillis()
        if (!fresh) gridCache[ch.id]?.let { if (now - it.first < 15 * 60_000L) return it.second.filter { e -> e.end > from && e.start < to } }
        var failed = false
        val list = gridGate.withPermit {
            val provider = try {
                // Catch-up channels: the full table includes past shows; others only have what's next.
                if (ch.hasArchive) api.epg(ch.id, full = true) else api.epg(ch.id, full = false, limit = 30)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Epg", "Grid guide unavailable for ${ch.id}", e)
                failed = true
                emptyList()
            }
            shift(provider).ifEmpty { shift(guide?.entries(ch.id).orEmpty()) }
        }
        if (failed && list.isEmpty()) return null
        gridCache[ch.id] = now to list
        return list.filter { it.end > from && it.start < to }
    }

    /** Applies the guide time offset to displayed times; raw times stay for catch-up URLs. */
    private fun shift(list: List<EpgEntry>): List<EpgEntry> {
        val o = settings().epgOffsetMin * 60L
        return if (o == 0L) list else list.map { it.copy(start = it.rawStart + o, end = it.rawEnd + o) }
    }

    fun clearGuideCache() {
        epgCache.clear()
        gridCache.clear()
        epgVersion++
    }

    private val rowGate = kotlinx.coroutines.sync.Semaphore(3)

    /** Loads now and next for a channel row, a few at a time so scrolling doesn't flood the provider. */
    suspend fun prefetchNowNext(id: Int) {
        if (epgCache.containsKey(id)) return
        rowGate.withPermit { if (!epgCache.containsKey(id)) shortEpgOrNull(id) }
    }

    /** What's on now and next; null until loaded, (null, null) when the channel has no guide. */
    fun nowNext(id: Int): Pair<EpgEntry?, EpgEntry?>? {
        minute
        epgVersion
        val list = epgCache[id]?.second ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val cur = list.firstOrNull { nowSec in it.start until it.end }
        val next = list.firstOrNull { it.start >= (cur?.end ?: nowSec) }
        return cur to next
    }

    fun nowPlaying(id: Int): String? {
        minute // read so callers recompose each minute
        val nowSec = System.currentTimeMillis() / 1000
        return epgCache[id]?.second?.firstOrNull { nowSec in it.start until it.end }?.title
    }

    companion object {
        fun yearIn(name: String): String? = CatalogParser.yearIn(name)
    }
}

private fun langOf(cats: List<Category>): Map<String, String> = buildMap {
    cats.forEach { c -> LanguageDetector.ofCategory(c.name)?.let { put(c.id, it) } }
}
