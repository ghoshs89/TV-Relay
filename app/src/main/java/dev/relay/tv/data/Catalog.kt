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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.builtins.serializer
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
    /** A to Z, sorted once when the library loads; case-insensitive, ties in library order. */
    @Volatile var moviesByName: List<Movie> = emptyList(); private set
    @Volatile var seriesByName: List<Series> = emptyList(); private set
    /** Bumped each time a library is installed or cleared, so cached title lists know to start over. */
    var libraryVersion by mutableIntStateOf(0); private set

    var adultCats by mutableStateOf<Set<String>>(emptySet()); private set

    @Volatile var channelById: Map<Int, Channel> = emptyMap(); private set
    @Volatile var movieById: Map<Int, Movie> = emptyMap(); private set
    @Volatile var seriesById: Map<Int, Series> = emptyMap(); private set

    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var updatedAt by mutableLongStateOf(0L); private set
    /** Bumped when the saved guide is cleared (a new guide arrived), so rows ask for their programmes again. */
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

    /** Your chosen version per channel (see [ChannelVersions]); set by the app from settings. */
    var preferredVersions: () -> Map<String, Int> = { emptyMap() }
    var settings: () -> Settings = { Settings() }
    var guide: XmltvGuide? = null

    /** Wired by the app: runs [applyCustom] off the screen thread. Without it the work is done in place (tests). */
    var appScope: kotlinx.coroutines.CoroutineScope? = null
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

    // ---------- Loading and refreshing ----------

    /** What a load or refresh is doing right now; null when idle. */
    var progress by mutableStateOf<LoadProgress?>(null); private set
    private var lastProgressAt = 0L

    /** Updates [progress] at most five times a second (downloads report every 64 KB). */
    private fun report(step: String, percent: Int?, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressAt < 200) return
        lastProgressAt = now
        progress = LoadProgress(step, percent)
    }

    private fun memory(): String {
        val rt = Runtime.getRuntime()
        return "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB"
    }

    /**
     * Loads the saved library if nothing is shown yet, then refreshes it from the provider when
     * [force] is set or it's over 6 hours old. Callers should launch this in the app's scope so
     * leaving a screen doesn't cancel it halfway.
     */
    suspend fun load(force: Boolean = false) {
        if (mutex.isLocked) AppLog.d("Catalog", "Load requested (force=$force) while another is running; waiting")
        mutex.withLock {
            loading = true
            error = null
            val started = System.currentTimeMillis()
            try {
                coroutineScope {
                    // Every 30 s, say where a long load has got to, so a stuck one shows up in the log.
                    val watch = launch {
                        while (true) {
                            delay(30_000)
                            AppLog.i(
                                "Catalog",
                                "Still working after ${(System.currentTimeMillis() - started) / 1000}s: " +
                                    "${progress?.text ?: "starting"}; memory ${memory()}",
                            )
                        }
                    }
                    try {
                        if (isEmpty && cacheComplete()) {
                            AppLog.i("Catalog", "Opening the saved library (${savedSize()})")
                            report("Reading your saved library", 0, force = true)
                            val t = System.currentTimeMillis()
                            val parsed = withContext(Dispatchers.Default) {
                                CatalogParser.parse(dir, strict = false) { f -> report("Reading your saved library", LoadSteps.span(0, 60, f)) }
                            }
                            AppLog.i("Catalog", "Read saved library in ${System.currentTimeMillis() - t} ms: ${parsed.counts}; ${parsed.timingText()}")
                            applyParsed(parsed, from = 60)
                        }
                        if (force || libraryDue()) {
                            try {
                                refresh(started)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                AppLog.e("Catalog", "Refresh failed after ${(System.currentTimeMillis() - started) / 1000}s; memory ${memory()}", e)
                                if (isEmpty) throw e
                                error = "Couldn't refresh (${e.message ?: e.javaClass.simpleName}). Showing your saved lists."
                            }
                        }
                    } finally {
                        watch.cancel()
                    }
                }
            } catch (e: CancellationException) {
                AppLog.w("Catalog", "Load cancelled after ${(System.currentTimeMillis() - started) / 1000}s at ${progress?.text}")
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: running out of memory on a big playlist must be logged too.
                AppLog.e("Catalog", "Load failed at ${progress?.text}; memory ${memory()}", e)
                error = when (e) {
                    is OutOfMemoryError -> "This playlist is too large for this device's memory"
                    else -> e.message ?: "Couldn't load your playlist"
                }
            } finally {
                loading = false
                progress = null
            }
        }
        // Outside the lock: a slow guide download must never hold up the next library refresh.
        refreshGuideIfNeeded(force = false)
    }

    private fun savedSize(): String = LoadSteps.megabytes(actions.sumOf { file(it).length() })

    /**
     * Downloads all six lists into a staging folder, checks every one parses as a complete list
     * and that nothing went from full to empty, and only then replaces the saved library with
     * them. A failed or suspicious refresh leaves the saved library untouched.
     */
    private suspend fun refresh(started: Long) {
        val staging = File(dir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        AppLog.i("Catalog", "Refreshing from ${api.host ?: "the first server"}; memory ${memory()}")
        try {
            val big = setOf("get_live_streams", "get_vod_streams", "get_series")
            val downloads = actions.map { a ->
                LoadSteps.Download(a, a in big).also { d ->
                    // Until the server says, expect about what this list weighed last time.
                    d.expected = file(a).takeIf { it.exists() && it.length() > 0 }?.length()
                }
            }
            fun reportDownload() {
                val f = LoadSteps.downloadFraction(downloads)
                val active = downloads.filter { !it.finished && it.big }.joinToString(", ") { LABEL[it.action] ?: it.action }
                val step = if (active.isEmpty()) "Downloading" else "Downloading $active"
                if (f != null) report(step, LoadSteps.span(0, LoadSteps.DOWNLOAD_END, f))
                else report("$step · ${LoadSteps.megabytes(downloads.sumOf { it.bytes })}", null)
            }
            report("Downloading", 0, force = true)
            coroutineScope {
                downloads.map { d ->
                    async {
                        val t = System.currentTimeMillis()
                        val info = api.download(d.action, File(staging, "${d.action}.json")) { bytes, total ->
                            d.bytes = bytes
                            if (total != null && total > 0) d.expected = total
                            reportDownload()
                        }
                        d.finished = true
                        reportDownload()
                        val secs = (System.currentTimeMillis() - t) / 1000.0
                        AppLog.i(
                            "Catalog",
                            "Downloaded ${d.action}: ${LoadSteps.megabytes(info.bytes)} in ${"%.1f".format(secs)}s from ${info.host}" +
                                (info.contentLength?.let { "" } ?: " (no size from server)"),
                        )
                    }
                }.awaitAll()
            }
            report("Checking the new lists", LoadSteps.DOWNLOAD_END, force = true)
            val t = System.currentTimeMillis()
            val fresh = withContext(Dispatchers.Default) {
                CatalogParser.parse(staging, strict = true) { f ->
                    report("Checking the new lists", LoadSteps.span(LoadSteps.DOWNLOAD_END, LoadSteps.CHECK_END, f))
                }
            }
            AppLog.i("Catalog", "Checked new lists in ${System.currentTimeMillis() - t} ms: ${fresh.counts}; ${fresh.timingText()}; memory ${memory()}")
            val old = CatalogCounts(rawChannels.size, movies.size, series.size)
            val verdict = RefreshGuard.check(old, fresh.counts)
            verdict.warnings.forEach { AppLog.w("Catalog", "Large drop after refresh: $it") }
            if (!verdict.accept) {
                AppLog.w("Catalog", "Refresh rejected: ${verdict.message} (${fresh.counts})")
                error = verdict.message
                return
            }
            withContext(Dispatchers.IO) { CatalogParser.commit(staging, dir) }
            applyParsed(fresh, from = LoadSteps.CHECK_END)
            AppLog.i(
                "Catalog",
                "Refreshed in ${(System.currentTimeMillis() - started) / 1000}s: ${channels.size} channels, " +
                    "${movies.size} movies, ${series.size} series",
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Channels the provider's full guide is kept for when guide search is on: your languages and
     * favourites, capped so the saved guide stays small. Set by the app.
     */
    var guideSearchScope: () -> (Channel) -> Boolean = { { false } }

    suspend fun refreshGuideIfNeeded(force: Boolean) {
        val g = guide ?: return
        if (channels.isEmpty()) return
        val own = settings().xmltvUrl.trim()
        val (url, key, list) = when {
            own.isNotEmpty() -> Triple(own, own, channels)
            settings().fullGuideSearch -> {
                val inScope = guideSearchScope()
                Triple(api.xmltvUrl() ?: return, PROVIDER_GUIDE, channels.filter(inScope).take(MAX_GUIDE_CHANNELS))
            }
            else -> return
        }
        try {
            if (force || g.needsRefresh(key)) {
                AppLog.i("Epg", if (key == PROVIDER_GUIDE) "Downloading the provider's full guide for ${list.size} channels" else "Downloading external guide")
                g.refresh(url, list, key)
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
        moviesByName = emptyList(); seriesByName = emptyList()
        libraryVersion++
        adultCats = emptySet()
        rawChannels = emptyList()
        channelById = emptyMap(); movieById = emptyMap(); seriesById = emptyMap()
        epgCache.clear()
        error = null
        dir.deleteRecursively()
    }

    /** Everything worked out from a parsed library, off the screen thread. */
    private class Indexes(p: ParsedCatalog, private val step: (Float) -> Unit) {
        val timings = LinkedHashMap<String, Long>()
        private var t = System.currentTimeMillis()
        private fun lap(name: String, done: Float) {
            val now = System.currentTimeMillis()
            timings[name] = now - t
            t = now
            step(done)
        }

        // Language groups (see LanguageDetector), worked out once per load.
        val liveCatLang = langOf(p.liveCats)
        val vodCatLang = langOf(p.vodCats)
        val seriesCatLang = langOf(p.seriesCats)
        val chLang = HashMap<Int, String>().also { m ->
            p.channels.forEach { c -> LanguageDetector.ofChannel(c.name, liveCatLang[c.categoryId])?.let { m[c.id] = it } }
            lap("channel languages", 0.2f)
        }
        val mvLang = HashMap<Int, String>().also { m ->
            p.movies.forEach { v -> LanguageDetector.ofTitle(v.name, vodCatLang[v.categoryId])?.let { m[v.id] = it } }
            lap("movie languages", 0.35f)
        }
        val srLang = HashMap<Int, String>().also { m ->
            p.series.forEach { v -> LanguageDetector.ofTitle(v.name, seriesCatLang[v.categoryId])?.let { m[v.id] = it } }
            lap("series languages", 0.45f)
        }
        val versionKeys = HashMap<Int, String>(p.channels.size * 2).also { m ->
            p.channels.forEach { c -> m[c.id] = ChannelVersions.key(c.name) + "|" + (chLang[c.id] ?: "") }
        }
        val quality = HashMap<Int, Int>(p.channels.size * 2).also { m ->
            p.channels.forEach { c -> m[c.id] = ChannelVersions.quality(c.name) }
            lap("channel versions", 0.6f)
        }
        val mvAdded = p.movies.sortedByDescending { it.added }
        val mvRated = p.movies.filter { it.rating > 0 }.sortedByDescending { it.rating }
        val srAdded = p.series.sortedByDescending { it.added }
        val srRated = p.series.filter { it.rating > 0 }.sortedByDescending { it.rating }
        val mvByName = sortedByLowerName(p.movies) { it.name }
        val srByName = sortedByLowerName(p.series) { it.name }
        val mvByCat = p.movies.groupBy { it.categoryId }
        val srByCat = p.series.groupBy { it.categoryId }
        val mvById = p.movies.associateBy { it.id }
        val srById = p.series.associateBy { it.id }.also { lap("sorting", 0.85f) }

        fun text() = timings.entries.joinToString(", ") { "${it.key} ${it.value} ms" }
    }

    private suspend fun applyParsed(p: ParsedCatalog, from: Int) {
        val t0 = System.currentTimeMillis()
        report("Preparing your lists", from, force = true)
        customGen.incrementAndGet()
        val renames = custom().renames
        val (sorted, chIndex) = withContext(Dispatchers.Default) {
            val ix = Indexes(p) { f -> report("Preparing your lists", LoadSteps.span(from, 99, f)) }
            val ci = buildChannelIndex(p.channels, renames, ix.versionKeys, ix.quality, ix.chLang)
            ix.timings["channel index"] = System.currentTimeMillis() - t0 - ix.timings.values.sum()
            ix to ci
        }
        report("Showing your lists", 99, force = true)
        val t1 = System.currentTimeMillis()
        liveCatLang = sorted.liveCatLang; vodCatLang = sorted.vodCatLang; seriesCatLang = sorted.seriesCatLang
        chLang = sorted.chLang; mvLang = sorted.mvLang; srLang = sorted.srLang
        rawVersionKeys = sorted.versionKeys
        rawQuality = sorted.quality
        indexMs = t1 - t0
        langVersion++
        movieById = sorted.mvById
        seriesById = sorted.srById
        adultCats = (p.liveCats + p.vodCats + p.seriesCats).filter { it.adult }.map { it.id }.toSet()
        liveCats = p.liveCats; vodCats = p.vodCats; seriesCats = p.seriesCats
        movies = p.movies; series = p.series
        moviesByCat = sorted.mvByCat; seriesByCat = sorted.srByCat
        rawChannels = p.channels
        install(chIndex)
        moviesByAdded = sorted.mvAdded; moviesByRating = sorted.mvRated
        seriesByAdded = sorted.srAdded; seriesByRating = sorted.srRated
        moviesByName = sorted.mvByName; seriesByName = sorted.srByName
        libraryVersion++
        updatedAt = file("get_live_streams").lastModified()
        AppLog.i("Catalog", "Prepared lists in $indexMs ms (${sorted.text()}); applied on screen thread in ${System.currentTimeMillis() - t1} ms")
        AppLog.i("Catalog", loadSummary().replace("\n", "; ") + "; ${versionsByKey.size} channels with several versions")
    }

    // ---------- Languages ----------

    private var liveCatLang: Map<String, String> = emptyMap()
    private var vodCatLang: Map<String, String> = emptyMap()
    private var seriesCatLang: Map<String, String> = emptyMap()
    @Volatile private var chLang: Map<Int, String> = emptyMap()
    private var mvLang: Map<Int, String> = emptyMap()
    private var srLang: Map<Int, String> = emptyMap()

    /** Original languages learned from TMDB when a title is opened; they correct the guesses. Saved. */
    private val learnedFile: File get() = File(ctx.filesDir, "learned_langs.json")
    private val learned: ConcurrentHashMap<String, String> by lazy {
        ConcurrentHashMap<String, String>().also { m ->
            try {
                if (learnedFile.exists()) {
                    m.putAll(Jsons.json.decodeFromString(LANG_MAP, learnedFile.readText()))
                }
            } catch (e: Exception) {
                AppLog.w("Catalog", "Saved title languages unreadable", e)
            }
        }
    }

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
        if (learned.put(key, g) != g) {
            langVersion++
            try {
                // Capped so it can't grow without limit.
                val keep = if (learned.size > 5000) learned.entries.take(5000).associate { it.key to it.value } else HashMap(learned)
                learnedFile.writeText(Jsons.json.encodeToString(LANG_MAP, keep))
            } catch (e: Exception) {
                AppLog.w("Catalog", "Couldn't save title languages", e)
            }
        }
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

    /** Channel lists with renames applied, and versions grouped and ranked. Built off the screen thread. */
    private class ChannelIndex(
        val channels: List<Channel>,
        val byId: Map<Int, Channel>,
        val byCat: Map<String, List<Channel>>,
        val versionKey: Map<Int, String>,
        val versionsByKey: Map<String, List<Channel>>,
        val versionRank: Map<Int, Int>,
        val sortKey: Map<Int, String>,
        val hd: Set<Int>,
        val langCounts: Map<String?, Int>,
    )

    private fun buildChannelIndex(
        raw: List<Channel>,
        renames: Map<Int, String>,
        rawKeys: Map<Int, String>,
        rawQuality: Map<Int, Int>,
        lang: Map<Int, String>,
    ): ChannelIndex {
        val ch = if (renames.isEmpty()) raw else raw.map { c -> renames[c.id]?.let { c.copy(name = it) } ?: c }
        val byId = ch.associateBy { it.id }
        // Keys and quality for provider names come from the background index; only renamed channels are redone.
        val keys = HashMap(rawKeys)
        val quality = HashMap(rawQuality)
        renames.keys.forEach { id ->
            byId[id]?.let { c ->
                keys[id] = ChannelVersions.key(c.name) + "|" + (lang[id] ?: "")
                quality[id] = ChannelVersions.quality(c.name)
            }
        }
        val groups = ChannelVersions.group(ch) { keys[it.id] ?: "#${it.id}" }
            .mapValues { (_, g) -> g.withIndex().sortedWith(compareBy({ -(quality[it.value.id] ?: 0) }, { it.index })).map { it.value } }
        val rank = HashMap<Int, Int>()
        groups.values.forEach { g -> g.forEachIndexed { i, c -> rank[c.id] = i } }
        // Sort keys, HD flags and language counts, worked out once here so list screens don't redo them per rebuild.
        val sortKeys = HashMap<Int, String>(ch.size * 2)
        val hd = HashSet<Int>()
        val langCounts = HashMap<String?, Int>()
        for (c in ch) {
            sortKeys[c.id] = ChannelKeys.sortKey(c.name)
            if (ChannelKeys.isHd(c.name)) hd.add(c.id)
            val l = lang[c.id]
            langCounts[l] = (langCounts[l] ?: 0) + 1
        }
        return ChannelIndex(
            channels = ch,
            byId = byId,
            byCat = ch.groupBy { it.categoryId },
            versionKey = keys.filterValues { it in groups },
            versionsByKey = groups,
            versionRank = rank,
            sortKey = sortKeys,
            hd = hd,
            langCounts = langCounts,
        )
    }

    private fun install(ix: ChannelIndex) {
        versionKey = ix.versionKey
        versionsByKey = ix.versionsByKey
        versionRank = ix.versionRank
        channels = ix.channels
        channelById = ix.byId
        channelsByCat = ix.byCat
        sortKeys = ix.sortKey
        hdIds = ix.hd
        channelLangCounts = ix.langCounts
    }

    /** Re-applies channel renames after the user edits them. */
    fun applyCustom() {
        val gen = customGen.incrementAndGet()
        val raw = rawChannels
        val renames = custom().renames
        val keys = rawVersionKeys
        val quality = rawQuality
        val lang = chLang
        val scope = appScope
        if (scope == null) {
            install(buildChannelIndex(raw, renames, keys, quality, lang))
            return
        }
        scope.launch {
            val ix = withContext(Dispatchers.Default) { buildChannelIndex(raw, renames, keys, quality, lang) }
            // A newer edit, or a newly loaded library, makes this result stale.
            if (gen == customGen.get() && raw === rawChannels) install(ix)
        }
    }

    private val customGen = java.util.concurrent.atomic.AtomicInteger()

    // ---------- Versions of one channel ----------

    @Volatile private var versionKey: Map<Int, String> = emptyMap()
    @Volatile private var versionsByKey: Map<String, List<Channel>> = emptyMap()
    @Volatile private var versionRank: Map<Int, Int> = emptyMap()
    @Volatile private var sortKeys: Map<Int, String> = emptyMap()
    @Volatile private var hdIds: Set<Int> = emptySet()

    /** Channels per language group (null = unknown), counted with the index; read with [channels]. */
    @Volatile var channelLangCounts: Map<String?, Int> = emptyMap(); private set

    private var rawVersionKeys: Map<Int, String> = emptyMap()
    private var rawQuality: Map<Int, Int> = emptyMap()

    fun versionKeyOf(id: Int): String? = versionKey[id]

    /** Every version of a channel, your choice first then best quality; empty when it has only one. */
    fun versionsOf(id: Int): List<Channel> {
        val k = versionKey[id] ?: return emptyList()
        val g = versionsByKey[k].orEmpty()
        val p = preferredVersions()[k] ?: return g
        val chosen = g.firstOrNull { it.id == p } ?: return g
        return listOf(chosen) + (g - chosen)
    }

    /** One entry per channel in a list, showing your chosen or the best version. */
    fun collapseVersions(list: List<Channel>, prefs: Map<String, Int> = preferredVersions()): List<Channel> {
        if (versionKey.isEmpty()) return list
        val keys = versionKey
        val rank = versionRank
        return ChannelVersions.collapse(list, { keys[it.id] }, { rank[it.id] ?: 0 }, { it.id }) { prefs[it] }
    }

    val versionGroupCount: Int get() = versionsByKey.size

    /** Read-only view for [buildLiveList]; safe to use from a background thread. */
    val liveData: LiveData = object : LiveData {
        override val channels: List<Channel> get() = this@Catalog.channels
        override fun byId(id: Int): Channel? = channelById[id]
        override fun inCategory(cat: String): List<Channel> = channelsByCat[cat].orEmpty()
        override fun lang(id: Int): String? = chLang[id]
        override fun sortKey(ch: Channel): String = sortKeys[ch.id] ?: ChannelKeys.sortKey(ch.name)
        override fun isHd(ch: Channel): Boolean = ch.id in hdIds
        override fun hasGuide(ch: Channel): Boolean = guide?.entries(ch.id)?.isNotEmpty() == true
        override fun collapse(list: List<Channel>, preferred: Map<String, Int>): List<Channel> = collapseVersions(list, preferred)
    }

    /** What the Movies and Series lists read, for building them off the screen thread. */
    val movieSource: TitleSource<Movie> = object : TitleSource<Movie> {
        override val byAdded: List<Movie> get() = this@Catalog.moviesByAdded
        override val byRating: List<Movie> get() = this@Catalog.moviesByRating
        override val byName: List<Movie> get() = this@Catalog.moviesByName
        override fun inCategory(cat: String): List<Movie> = moviesByCat[cat].orEmpty()
        override fun byId(id: Int): Movie? = movieById[id]
        override fun lang(id: Int): String? = movieLang(id)
        override fun id(t: Movie): Int = t.id
        override fun category(t: Movie): String = t.categoryId
    }

    val seriesSource: TitleSource<Series> = object : TitleSource<Series> {
        override val byAdded: List<Series> get() = this@Catalog.seriesByAdded
        override val byRating: List<Series> get() = this@Catalog.seriesByRating
        override val byName: List<Series> get() = this@Catalog.seriesByName
        override fun inCategory(cat: String): List<Series> = seriesByCat[cat].orEmpty()
        override fun byId(id: Int): Series? = seriesById[id]
        override fun lang(id: Int): String? = seriesLang(id)
        override fun id(t: Series): Int = t.id
        override fun category(t: Series): String = t.categoryId
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
        CacheLimits.prune(epgCache, max = 800, drop = 200)
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
        CacheLimits.prune(gridCache, max = 800, drop = 200)
        return list.filter { it.end > from && it.start < to }
    }

    /** Applies the guide time offset to displayed times; raw times stay for catch-up URLs. */
    private fun shift(list: List<EpgEntry>): List<EpgEntry> {
        val o = settings().epgOffsetMin * 60L
        return if (o == 0L) list else list.map { it.copy(start = it.rawStart + o, end = it.rawEnd + o) }
    }

    /**
     * Upcoming and current programmes matching a search, from the saved guide and from guide
     * data already loaded for channel rows. Returns (channel, programme), soonest first.
     */
    fun searchGuide(match: (String) -> Boolean, limit: Int = 40): List<Pair<Channel, EpgEntry>> {
        val nowSec = System.currentTimeMillis() / 1000
        val found = LinkedHashMap<String, Pair<Channel, EpgEntry>>()
        for ((id, e) in guide?.search(match, limit * 2).orEmpty()) {
            val ch = channelById[id] ?: continue
            val shifted = shift(listOf(e)).first()
            // One result per programme even when a channel has several versions.
            found["${versionKey[id] ?: id}:${e.rawStart}"] = ch to shifted
        }
        for ((id, cached) in epgCache) {
            val ch = channelById[id] ?: continue
            for (e in cached.second) {
                if (e.end > nowSec && match(e.title)) found.putIfAbsent("${versionKey[id] ?: id}:${e.rawStart}", ch to e)
            }
        }
        return found.values.sortedBy { it.second.start }.take(limit)
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
        private val LABEL = mapOf("get_live_streams" to "channels", "get_vod_streams" to "movies", "get_series" to "series")
        const val PROVIDER_GUIDE = "provider"
        const val MAX_GUIDE_CHANNELS = 1500

        fun yearIn(name: String): String? = CatalogParser.yearIn(name)
    }
}

private fun langOf(cats: List<Category>): Map<String, String> = buildMap {
    cats.forEach { c -> LanguageDetector.ofCategory(c.name)?.let { put(c.id, it) } }
}

private val LANG_MAP = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
