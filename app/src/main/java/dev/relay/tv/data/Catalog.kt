package dev.relay.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
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
        val next = LoadProgress(step, percent)
        Snapshot.withMutableSnapshot { progress = next }
    }

    /** Seconds since this process started, for the log: shows how long a cold start took to reach each point. */
    private fun sinceStart(): String {
        val ms = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()
        return "%.1f s after start".format(ms / 1000.0)
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
        guide?.ensureLoaded()
        val wait = loadLocked(force)
        if (wait > 0) {
            // An overdue library waits a little after start-up (outside the lock and with nothing shown as loading),
            // then asks again: by then someone else may have refreshed it.
            delay(wait)
            load(force = false)
            return
        }
        // Outside the lock: a slow guide download must never hold up the next library refresh.
        refreshGuideIfNeeded(force = false)
    }

    /** Uptime (SystemClock.elapsedRealtime) when this process installed its first library from saved data; negative until then. */
    @Volatile private var installedAt = -1L

    /** The locked part of [load]. Returns how long to wait before the refresh, when it was put off; 0 when nothing more is due. */
    private suspend fun loadLocked(force: Boolean): Long {
        if (mutex.isLocked) AppLog.d("Catalog", "Load requested (force=$force) while another is running; waiting")
        var wait = 0L
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
                        if (isEmpty && cacheComplete() && !loadFromSnapshot()) {
                            AppLog.i("Catalog", "Opening the saved library (${savedSize()})")
                            report("Reading your saved library", 0, force = true)
                            val t = System.currentTimeMillis()
                            val parsed = CatalogParser.parseConcurrently(dir, strict = false) { f ->
                                report("Reading your saved library", LoadSteps.span(0, 60, f))
                            }
                            AppLog.i("Catalog", "Read saved library in ${System.currentTimeMillis() - t} ms: ${parsed.counts}; ${parsed.timingText()}")
                            applyParsed(parsed, from = 60)
                        }
                        if (installedAt < 0 && !isEmpty) installedAt = android.os.SystemClock.elapsedRealtime()
                        val due = force || libraryDue()
                        wait = Freshness.startDelay(installedAt, android.os.SystemClock.elapsedRealtime(), due, force, DeviceTier.current.refreshStartDelayMs)
                        if (wait > 0) {
                            val hours = (System.currentTimeMillis() - file("get_live_streams").lastModified()) / 3_600_000
                            AppLog.i("Catalog", "Library is $hours h old; refreshing in ${(wait + 500) / 1000} s")
                        } else if (due) {
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
        return wait
    }

    private fun savedSize(): String = LoadSteps.megabytes(actions.sumOf { file(it).length() })

    // ---------- Saved copy of the prepared library (see LibrarySnapshot) ----------

    private val snapFile: File get() = File(dir, LibrarySnapshot.FILE_NAME)

    /** The six JSON lists as they are now, in the order of [actions]; the saved copy is only valid for exactly these. */
    private fun stamps(): List<LibrarySnapshot.FileStamp> =
        actions.map { a -> file(a).let { LibrarySnapshot.FileStamp(it.length(), it.lastModified()) } }

    /** Bumped by each load, refresh and clear; a pending save of an older library sees that and stops. */
    private val snapGen = java.util.concurrent.atomic.AtomicInteger()

    /** One line for the report in Settings: was the saved copy used, and why not. */
    @Volatile var snapshotStatus: String = "not tried yet"
        private set

    /** The saved copy could not be read; the cause is for the log. */
    private class SnapshotUnusable(cause: Throwable) : Exception(cause)

    /** Ignores the saved copy, says why, and deletes it so the next JSON load writes a fresh one. */
    private fun dropSnapshot(reason: String, cause: Throwable? = null) {
        if (cause != null) AppLog.w("Catalog", "Saved copy not used: $reason", cause) else AppLog.i("Catalog", "Saved copy not used: $reason")
        snapshotStatus = "not used ($reason)"
        try {
            snapFile.delete()
        } catch (e: Exception) {
            AppLog.w("Catalog", "Couldn't delete the saved copy", e)
        }
    }

    /**
     * Installs the library from the saved copy when it matches this version of Relay and the six
     * JSON lists exactly. Returns false (after logging why) when it was not used, and the caller
     * reads the JSON as before. Channels, movies and series are read at the same time and installed
     * in order as each is ready, so Live TV can show while movies are still being read.
     */
    private suspend fun loadFromSnapshot(): Boolean {
        val started = System.currentTimeMillis()
        val opened = when (val o = LibrarySnapshot.open(snapFile, stamps())) {
            is LibrarySnapshot.NotUsed -> {
                if (o.reason == LibrarySnapshot.Reason.MISSING) {
                    AppLog.i("Catalog", "Saved copy not used: ${o.reason.text}")
                    snapshotStatus = "not used (${o.reason.text})"
                } else {
                    dropSnapshot(o.toString())
                }
                return false
            }
            is LibrarySnapshot.Opened -> o
        }
        report("Reading your saved library", 0, force = true)
        val readMs = ConcurrentHashMap<String, Long>()
        try {
            supervisorScope {
                val gate = Semaphore(DeviceTier.current.parallelLists)
                fun <T> read(name: String, block: () -> T): Deferred<T> = async(Dispatchers.Default) {
                    gate.withPermit {
                        val t = System.currentTimeMillis()
                        try {
                            block().also { readMs[name] = System.currentTimeMillis() - t }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw SnapshotUnusable(e)
                        }
                    }
                }
                val cats = read("categories") { opened.readCategories() }
                val channels = read("channels") { opened.readChannels() }
                val movies = read("movies") { opened.readMovies() }
                val series = read("series") { opened.readSeries() }
                installStages(
                    Stages(
                        channels = { laps -> ChannelStage.fromSnapshot(cats.await(), channels.await(), laps) },
                        movies = { _, laps -> MovieStage.fromSnapshot(movies.await(), laps) },
                        series = { _, laps -> SeriesStage.fromSnapshot(series.await(), laps) },
                    ),
                    from = 0,
                )
            }
        } catch (e: SnapshotUnusable) {
            dropSnapshot("damaged", e.cause)
            return false
        }
        val saved = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(opened.header.writtenAt))
        snapshotStatus = "used, ${LoadSteps.megabytes(opened.sizeBytes)}, saved $saved"
        AppLog.i(
            "Catalog",
            "Library from saved copy in ${System.currentTimeMillis() - started} ms (channels ${readMs["channels"]} ms, " +
                "movies ${readMs["movies"]} ms, series ${readMs["series"]} ms; ${LoadSteps.megabytes(opened.sizeBytes)})",
        )
        return true
    }

    /**
     * After a library was prepared from JSON, saves a ready-to-use copy so the next start can skip
     * that work. Starts 5 seconds after the last stage so it doesn't compete with the first screen,
     * runs at background priority, and is dropped if a newer load or refresh comes first.
     */
    private fun writeSnapshotLater() {
        val gen = snapGen.incrementAndGet()
        val scope = appScope ?: return
        // Only references to what was just installed; nothing is copied here.
        val source = LibrarySnapshot.Source(
            stamps = stamps(),
            liveCats = liveCats, vodCats = vodCats, seriesCats = seriesCats,
            channels = rawChannels, chLang = chLang, versionKeys = rawVersionKeys, quality = rawQuality,
            sortKeys = rawSortKeys, hd = rawHd,
            movies = movies, mvLang = mvLang,
            moviesByAdded = moviesByAdded, moviesByRating = moviesByRating, moviesByName = moviesByName,
            series = series, srLang = srLang,
            seriesByAdded = seriesByAdded, seriesByRating = seriesByRating, seriesByName = seriesByName,
        )
        scope.launch {
            delay(DeviceTier.current.snapshotDelayMs)
            if (gen != snapGen.get()) return@launch
            withContext(Dispatchers.IO) { saveSnapshot(source, gen) }
        }
    }

    private fun saveSnapshot(source: LibrarySnapshot.Source, gen: Int) {
        val target = snapFile
        val last = target.takeIf { it.exists() }?.length() ?: 0L
        val needed = if (last > 0) last * 3 else 200L * 1_048_576
        val free = ctx.filesDir.usableSpace
        if (free < needed) {
            AppLog.w("Catalog", "Not saving a copy of the library: ${LoadSteps.megabytes(free)} free, ${LoadSteps.megabytes(needed)} needed")
            snapshotStatus = "not saved (low storage)"
            return
        }
        val tid = android.os.Process.myTid()
        val before = android.os.Process.getThreadPriority(tid)
        val t = System.currentTimeMillis()
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val bytes = LibrarySnapshot.write(target, source, System.currentTimeMillis()) { gen != snapGen.get() }
            val secs = (System.currentTimeMillis() - t) / 1000.0
            AppLog.i("Catalog", "Saved a copy of the library: ${LoadSteps.megabytes(bytes)} in ${"%.1f".format(secs)} s")
            snapshotStatus = "not used (read from the lists); new copy saved, ${LoadSteps.megabytes(bytes)}"
        } catch (e: LibrarySnapshot.SnapshotCancelled) {
            AppLog.d("Catalog", "Saving a copy of the library stopped: a newer load started")
        } catch (e: Exception) {
            AppLog.w("Catalog", "Couldn't save a copy of the library", e)
            snapshotStatus = "not saved (${e.message ?: e.javaClass.simpleName})"
        } finally {
            try {
                android.os.Process.setThreadPriority(before)
            } catch (e: Exception) {
                // The thread belongs to a pool; nothing to do if its priority can't be put back.
            }
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
            val fresh = CatalogParser.parseConcurrently(staging, strict = true) { f ->
                report("Checking the new lists", LoadSteps.span(LoadSteps.DOWNLOAD_END, LoadSteps.CHECK_END, f))
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
            snapGen.incrementAndGet() // the saved copy is about to be out of date; a pending save of it is pointless
            withContext(Dispatchers.IO) { CatalogParser.commit(staging, dir) }
            applyParsed(fresh, from = LoadSteps.CHECK_END, reuse = true)
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
        // One at a time: two callers at start-up used to download the same guide twice. The second
        // waits here and then finds the guide fresh.
        guideLock.withLock {
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
    }

    private val guideLock = Mutex()

    fun clear() {
        liveCats = emptyList(); vodCats = emptyList(); seriesCats = emptyList()
        channels = emptyList(); movies = emptyList(); series = emptyList()
        channelsByCat = emptyMap(); moviesByCat = emptyMap(); seriesByCat = emptyMap()
        moviesByAdded = emptyList(); moviesByRating = emptyList()
        seriesByAdded = emptyList(); seriesByRating = emptyList()
        moviesByName = emptyList(); seriesByName = emptyList()
        libraryVersion++
        langVersion++
        adultCats = emptySet()
        rawChannels = emptyList()
        channelById = emptyMap(); movieById = emptyMap(); seriesById = emptyMap()
        liveCatLang = emptyMap(); vodCatLang = emptyMap(); seriesCatLang = emptyMap()
        liveCatKind = emptyMap(); vodCatKind = emptyMap(); seriesCatKind = emptyMap()
        chLang = emptyMap(); mvLang = emptyMap(); srLang = emptyMap()
        chKind = emptyMap()
        channelLangCounts = emptyMap()
        channelKindCounts = emptyMap()
        versionKey = emptyMap(); versionsByKey = emptyMap(); versionRank = emptyMap()
        sortKeys = emptyMap(); hdIds = emptySet()
        rawVersionKeys = emptyMap(); rawQuality = emptyMap(); rawSortKeys = emptyMap(); rawHd = emptySet()
        installedAt = -1L
        updatedAt = 0L
        epgCache.clear()
        gridCache.clear()
        epgVersion++
        learned.clear()
        guide?.clear()
        searchChannels.clear(); searchMovies.clear(); searchSeries.clear()
        error = null
        snapGen.incrementAndGet()
        customGen.incrementAndGet()
        snapshotStatus = "cleared"
        val currentDir = File(ctx.filesDir, "catalog")
        val deadDir = File(ctx.filesDir, "catalog_del_${System.nanoTime()}")
        val deadLearned = File(ctx.filesDir, "learned_del_${System.nanoTime()}")
        val renamedDir = currentDir.exists() && currentDir.renameTo(deadDir)
        val renamedLearned = learnedFile.exists() && learnedFile.renameTo(deadLearned)
        val cleanup = {
            if (renamedDir) deadDir.deleteRecursively() else currentDir.deleteRecursively()
            if (renamedLearned) deadLearned.delete() else learnedFile.delete()
        }
        appScope?.launch(Dispatchers.IO) { cleanup() } ?: cleanup()
    }

    /** Times each step of preparing a library and reports progress as it goes. */
    private class Laps(private val step: (Float) -> Unit) {
        val timings = LinkedHashMap<String, Long>()
        private var t = System.currentTimeMillis()
        fun lap(name: String, done: Float) {
            val now = System.currentTimeMillis()
            timings[name] = now - t
            t = now
            step(done)
        }

        fun text() = timings.entries.joinToString(", ") { "${it.key} ${it.value} ms" }
    }

    /**
     * Stage 1, off the screen thread: what Live TV needs. Category languages are cheap, so all three kinds are done here.
     * A stage is a plain holder; it comes from a parsed library ([from]) or from the saved copy ([fromSnapshot]).
     */
    private class ChannelStage(
        val liveCats: List<Category>,
        val vodCats: List<Category>,
        val seriesCats: List<Category>,
        val channels: List<Channel>,
        val liveCatLang: Map<String, String>,
        val vodCatLang: Map<String, String>,
        val seriesCatLang: Map<String, String>,
        val liveCatKind: Map<String, String>,
        val vodCatKind: Map<String, String>,
        val seriesCatKind: Map<String, String>,
        val chLang: Map<Int, String>,
        val versionKeys: Map<Int, String>,
        val quality: Map<Int, Int>,
        val sortKeys: Map<Int, String>,
        val hd: Set<Int>,
    ) {
        companion object {
            fun from(p: ParsedCatalog, laps: Laps, previous: LibraryDerive.PreviousChannels? = null): ChannelStage {
                val live = LibraryDerive.categoryLangs(p.liveCats)
                val vod = LibraryDerive.categoryLangs(p.vodCats)
                val series = LibraryDerive.categoryLangs(p.seriesCats)
                val liveKinds = ContentKinds.liveCategoryKinds(p.liveCats)
                val vodKinds = ContentKinds.titleCategoryKinds(p.vodCats)
                val seriesKinds = ContentKinds.titleCategoryKinds(p.seriesCats)
                // One pass: each name is cleaned once and yields language, version key, quality, sort key and HD flag.
                // On a refresh, channels that did not change take their facts from the library already installed.
                val m = LibraryDerive.channels(p.channels, live, previous)
                if (previous != null) {
                    AppLog.i("Catalog", "Reused ${"%,d".format(m.reused)} of ${"%,d".format(p.channels.size)} channel facts; worked out ${"%,d".format(p.channels.size - m.reused)}")
                }
                laps.lap("channel texts", 0.7f)
                return ChannelStage(
                    p.liveCats, p.vodCats, p.seriesCats, p.channels, live, vod, series,
                    liveKinds, vodKinds, seriesKinds,
                    m.lang, m.versionKeys, m.quality, m.sortKeys, m.hd,
                )
            }

            fun fromSnapshot(cats: LibrarySnapshot.LoadedCategories, ch: LibrarySnapshot.LoadedChannels, laps: Laps): ChannelStage {
                val live = LibraryDerive.categoryLangs(cats.live)
                val vod = LibraryDerive.categoryLangs(cats.vod)
                val series = LibraryDerive.categoryLangs(cats.series)
                val liveKinds = ContentKinds.liveCategoryKinds(cats.live)
                val vodKinds = ContentKinds.titleCategoryKinds(cats.vod)
                val seriesKinds = ContentKinds.titleCategoryKinds(cats.series)
                laps.lap("channel texts", 0.7f)
                return ChannelStage(
                    cats.live, cats.vod, cats.series, ch.channels, live, vod, series,
                    liveKinds, vodKinds, seriesKinds,
                    ch.lang, ch.versionKeys, ch.quality, ch.sortKeys, ch.hd,
                )
            }
        }
    }

    /** Stage 2, off the screen thread: everything the Movies tab reads. */
    private class MovieStage(
        val movies: List<Movie>,
        val lang: Map<Int, String>,
        val added: List<Movie>,
        val rated: List<Movie>,
        val byName: List<Movie>,
        val byCat: Map<String, List<Movie>>,
        val byId: Map<Int, Movie>,
    ) {
        companion object {
            fun from(p: ParsedCatalog, vodCatLang: Map<String, String>, laps: Laps, previous: LibraryDerive.PreviousTitles<Movie>? = null): MovieStage {
                val d = LibraryDerive.movies(p.movies, vodCatLang, previous) { laps.lap("movie languages", 0.4f) }
                if (previous != null) {
                    AppLog.i("Catalog", "Reused ${"%,d".format(d.reused)} of ${"%,d".format(p.movies.size)} movie languages; worked out ${"%,d".format(p.movies.size - d.reused)}")
                }
                return of(p.movies, d.lang, d.added, d.rated, d.byName, laps)
            }

            fun fromSnapshot(m: LibrarySnapshot.LoadedMovies, laps: Laps): MovieStage =
                of(m.movies, m.lang, m.added, m.rated, m.byName, laps)

            private fun of(
                movies: List<Movie>, lang: Map<Int, String>, added: List<Movie>, rated: List<Movie>, byName: List<Movie>, laps: Laps,
            ): MovieStage {
                val byCat = movies.groupBy { it.categoryId }
                val byId = movies.associateBy { it.id }.also { laps.lap("movie lists", 0.9f) }
                return MovieStage(movies, lang, added, rated, byName, byCat, byId)
            }
        }
    }

    /** Stage 3, off the screen thread: everything the Series tab reads. */
    private class SeriesStage(
        val series: List<Series>,
        val lang: Map<Int, String>,
        val added: List<Series>,
        val rated: List<Series>,
        val byName: List<Series>,
        val byCat: Map<String, List<Series>>,
        val byId: Map<Int, Series>,
    ) {
        companion object {
            fun from(p: ParsedCatalog, seriesCatLang: Map<String, String>, laps: Laps, previous: LibraryDerive.PreviousTitles<Series>? = null): SeriesStage {
                val d = LibraryDerive.series(p.series, seriesCatLang, previous) { laps.lap("series languages", 0.4f) }
                if (previous != null) {
                    AppLog.i("Catalog", "Reused ${"%,d".format(d.reused)} of ${"%,d".format(p.series.size)} series languages; worked out ${"%,d".format(p.series.size - d.reused)}")
                }
                return of(p.series, d.lang, d.added, d.rated, d.byName, laps)
            }

            fun fromSnapshot(m: LibrarySnapshot.LoadedSeries, laps: Laps): SeriesStage =
                of(m.series, m.lang, m.added, m.rated, m.byName, laps)

            private fun of(
                series: List<Series>, lang: Map<Int, String>, added: List<Series>, rated: List<Series>, byName: List<Series>, laps: Laps,
            ): SeriesStage {
                val byCat = series.groupBy { it.categoryId }
                val byId = series.associateBy { it.id }.also { laps.lap("series lists", 0.9f) }
                return SeriesStage(series, lang, added, rated, byName, byCat, byId)
            }
        }
    }

    /** Where the three stages come from: worked out from parsed lists, or read from the saved copy. */
    private class Stages(
        val channels: suspend (Laps) -> ChannelStage,
        val movies: suspend (ChannelStage, Laps) -> MovieStage,
        val series: suspend (ChannelStage, Laps) -> SeriesStage,
    )

    /** Prepares a library read from the provider's JSON and then keeps a saved copy of the result (see [LibrarySnapshot]). */
    private suspend fun applyParsed(p: ParsedCatalog, from: Int, reuse: Boolean = false) {
        // A refresh reuses what was worked out for unchanged items (references to the installed library, no copies).
        val prevCh = if (reuse && rawChannels.isNotEmpty()) {
            LibraryDerive.PreviousChannels(
                rawChannels, liveCatLang,
                LibraryDerive.ChannelMaps(chLang, rawVersionKeys, rawQuality, rawSortKeys, rawHd),
            )
        } else null
        val prevMv = if (reuse && movies.isNotEmpty()) LibraryDerive.PreviousTitles(movies, vodCatLang, mvLang) else null
        val prevSr = if (reuse && series.isNotEmpty()) LibraryDerive.PreviousTitles(series, seriesCatLang, srLang) else null
        installStages(
            Stages(
                channels = { laps -> ChannelStage.from(p, laps, prevCh) },
                movies = { cs, laps -> MovieStage.from(p, cs.vodCatLang, laps, prevMv) },
                series = { cs, laps -> SeriesStage.from(p, cs.seriesCatLang, laps, prevSr) },
            ),
            from,
        )
        writeSnapshotLater()
    }

    /**
     * Installs a library in three stages (channels, movies, series), each as soon as it is ready, so
     * Live TV works while the big lists are still being indexed. [loading] stays true until the last
     * stage, because the caller's load() is still running. The JSON path and the saved copy both come
     * through here, so they show the same progress and write the same log lines.
     */
    private suspend fun installStages(stages: Stages, from: Int) {
        val t0 = System.currentTimeMillis()
        customGen.incrementAndGet()
        val renames = custom().renames
        // Progress: channels fill 0 to 35 of the span, movies 35 to 80, series 80 to 99.
        fun stage(name: String, lo: Int, hi: Int): Laps {
            report(name, LoadSteps.span(from, 99, lo / 100f), force = true)
            return Laps { f -> report(name, LoadSteps.span(from, 99, (lo + (hi - lo) * f) / 100f)) }
        }

        // Stage 1: channels.
        val chLaps = stage("Preparing channels", 0, 35)
        val (cs, chIndex) = withContext(Dispatchers.Default) {
            val st = stages.channels(chLaps)
            val ci = buildChannelIndex(st.channels, renames, st.versionKeys, st.quality, st.sortKeys, st.hd, st.chLang, st.liveCatKind)
            chLaps.lap("channel index", 1f)
            st to ci
        }
        liveCatLang = cs.liveCatLang; vodCatLang = cs.vodCatLang; seriesCatLang = cs.seriesCatLang
        liveCatKind = cs.liveCatKind; vodCatKind = cs.vodCatKind; seriesCatKind = cs.seriesCatKind
        chLang = cs.chLang
        rawVersionKeys = cs.versionKeys
        rawQuality = cs.quality
        rawSortKeys = cs.sortKeys
        rawHd = cs.hd
        adultCats = (cs.liveCats + cs.vodCats + cs.seriesCats).filter { it.adult }.map { it.id }.toSet()
        liveCats = cs.liveCats
        rawChannels = cs.channels
        install(chIndex)
        langVersion++
        val channelsMs = System.currentTimeMillis() - t0
        AppLog.i("Catalog", "Channels ready after $channelsMs ms, ${sinceStart()} (${chLaps.text()})")

        // Stage 2: movies.
        val mvLaps = stage("Preparing movies", 35, 80)
        val ms = withContext(Dispatchers.Default) { stages.movies(cs, mvLaps) }
        mvLang = ms.lang
        movieById = ms.byId
        vodCats = cs.vodCats
        movies = ms.movies
        moviesByCat = ms.byCat
        moviesByAdded = ms.added; moviesByRating = ms.rated
        moviesByName = ms.byName
        libraryVersion++
        langVersion++

        // Stage 3: series.
        val srLaps = stage("Preparing series", 80, 99)
        val ss = withContext(Dispatchers.Default) { stages.series(cs, srLaps) }
        srLang = ss.lang
        seriesById = ss.byId
        seriesCats = cs.seriesCats
        series = ss.series
        seriesByCat = ss.byCat
        seriesByAdded = ss.added; seriesByRating = ss.rated
        seriesByName = ss.byName
        libraryVersion++
        langVersion++

        indexMs = System.currentTimeMillis() - t0
        updatedAt = file("get_live_streams").lastModified()
        AppLog.i(
            "Catalog",
            "Channels ready after $channelsMs ms; all lists after $indexMs ms, ${sinceStart()} " +
                "(${chLaps.text()}; ${mvLaps.text()}; ${srLaps.text()})",
        )
        AppLog.i("Catalog", loadSummary().replace("\n", "; ") + "; ${versionsByKey.size} channels with several versions")
    }

    // ---------- Languages and content types ----------

    private var liveCatLang: Map<String, String> = emptyMap()
    private var vodCatLang: Map<String, String> = emptyMap()
    private var seriesCatLang: Map<String, String> = emptyMap()
    @Volatile private var liveCatKind: Map<String, String> = emptyMap()
    @Volatile private var vodCatKind: Map<String, String> = emptyMap()
    @Volatile private var seriesCatKind: Map<String, String> = emptyMap()
    @Volatile private var chLang: Map<Int, String> = emptyMap()
    @Volatile private var mvLang: Map<Int, String> = emptyMap()
    @Volatile private var srLang: Map<Int, String> = emptyMap()
    @Volatile private var chKind: Map<Int, String> = emptyMap()

    /** Original languages learned from TMDB when a title is opened; they correct the guesses. Saved. */
    private val learnedFile: File get() = File(ctx.filesDir, "learned_langs.json")
    private val learned: LearnedLangs by lazy {
        LearnedLangs().also { m ->
            try {
                if (learnedFile.exists()) m.load(Jsons.json.decodeFromString(LANG_MAP, learnedFile.readText()))
            } catch (e: Exception) {
                AppLog.w("Catalog", "Saved title languages unreadable", e)
            }
        }
    }
    private val learnedWrite = Any()

    var langVersion by mutableIntStateOf(0); private set
    var indexMs = 0L; private set

    fun channelLang(id: Int): String? = chLang[id]
    fun movieLang(id: Int): String? = learned.movie(id) ?: mvLang[id]
    fun seriesLang(id: Int): String? = learned.series(id) ?: srLang[id]
    fun liveCatLang(id: String): String? = liveCatLang[id]
    fun vodCatLang(id: String): String? = vodCatLang[id]
    fun seriesCatLang(id: String): String? = seriesCatLang[id]
    fun vodCatKindOf(id: String): String? = vodCatKind[id]
    fun seriesCatKindOf(id: String): String? = seriesCatKind[id]

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
        val guess: (Boolean, Int) -> String? = { movie, id -> if (movie) mvLang[id] else srLang[id] }
        when (learned.learn(key, g, guess)) {
            LearnedLangs.Change.None -> return
            // Lists are only built again when the title really moved to another language group. Learning what
            // was already guessed (the usual case) is saved and nothing on screen is redone.
            LearnedLangs.Change.Moved -> langVersion++
            LearnedLangs.Change.Confirmed -> Unit
        }
        val keep = learned.snapshot(LearnedLangs.MAX_SAVED)
        val save = {
            synchronized(learnedWrite) {
                try {
                    learnedFile.writeText(Jsons.json.encodeToString(LANG_MAP, keep))
                } catch (e: Exception) {
                    AppLog.w("Catalog", "Couldn't save title languages", e)
                }
            }
        }
        // Off the screen thread: this is called while a detail page is opening.
        appScope?.launch(Dispatchers.IO) { save() } ?: save()
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
        val kind: Map<Int, String>,
        val kindCounts: Map<String, Int>,
    )

    private fun buildChannelIndex(
        raw: List<Channel>,
        renames: Map<Int, String>,
        rawKeys: Map<Int, String>,
        rawQuality: Map<Int, Int>,
        rawSortKeys: Map<Int, String>,
        rawHd: Set<Int>,
        lang: Map<Int, String>,
        catKinds: Map<String, String>,
    ): ChannelIndex {
        val ch = if (renames.isEmpty()) raw else raw.map { c -> renames[c.id]?.let { c.copy(name = it) } ?: c }
        val byId = ch.associateBy { it.id }
        // Everything derived from a provider name comes from the background index; only renamed channels are redone.
        // Without renames the raw maps are used as they are (nothing writes to them), which saves four copies of
        // a map with one entry per channel, in time and in memory.
        val keysCopy = if (renames.isEmpty()) null else HashMap(rawKeys)
        val qualityCopy = if (renames.isEmpty()) null else HashMap(rawQuality)
        val sortCopy = if (renames.isEmpty()) null else HashMap(rawSortKeys)
        val hdCopy = if (renames.isEmpty()) null else HashSet(rawHd)
        renames.keys.forEach { id ->
            byId[id]?.let { c ->
                // The renamed name decides key, quality, sorting and HD; the language stays the provider name's.
                keysCopy!![id] = ChannelVersions.key(c.name) + "|" + (lang[id] ?: "")
                qualityCopy!![id] = ChannelVersions.quality(c.name)
                sortCopy!![id] = ChannelKeys.sortKey(c.name)
                if (ChannelKeys.isHd(c.name)) hdCopy!!.add(id) else hdCopy!!.remove(id)
            }
        }
        val keys: Map<Int, String> = keysCopy ?: rawKeys
        val quality: Map<Int, Int> = qualityCopy ?: rawQuality
        val sortKeys: Map<Int, String> = sortCopy ?: rawSortKeys
        val hd: Set<Int> = hdCopy ?: rawHd
        val groups = ChannelVersions.group(ch) { keys[it.id] ?: "#${it.id}" }
            .mapValues { (_, g) -> g.withIndex().sortedWith(compareBy({ -(quality[it.value.id] ?: 0) }, { it.index })).map { it.value } }
        val rank = HashMap<Int, Int>()
        groups.values.forEach { g -> g.forEachIndexed { i, c -> rank[c.id] = i } }
        // Language and content-type counts, worked out once here so list screens don't redo them per rebuild.
        val langCounts = HashMap<String?, Int>()
        val kinds = HashMap<Int, String>()
        val kindCounts = HashMap<String, Int>()
        for (c in ch) {
            val l = lang[c.id]
            langCounts[l] = (langCounts[l] ?: 0) + 1
            ContentKinds.ofChannel(c.name, catKinds[c.categoryId])?.let { k ->
                kinds[c.id] = k
                kindCounts[k] = (kindCounts[k] ?: 0) + 1
            }
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
            kind = kinds,
            kindCounts = kindCounts,
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
        chKind = ix.kind
        channelKindCounts = ix.kindCounts
    }

    /** Re-applies channel renames after the user edits them. */
    fun applyCustom() {
        val gen = customGen.incrementAndGet()
        val raw = rawChannels
        val renames = custom().renames
        val keys = rawVersionKeys
        val quality = rawQuality
        val sortKeys = rawSortKeys
        val hd = rawHd
        val lang = chLang
        val catKinds = liveCatKind
        val scope = appScope
        if (scope == null) {
            install(buildChannelIndex(raw, renames, keys, quality, sortKeys, hd, lang, catKinds))
            return
        }
        scope.launch {
            val ix = withContext(Dispatchers.Default) { buildChannelIndex(raw, renames, keys, quality, sortKeys, hd, lang, catKinds) }
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

    /** Channels per language group (null = unknown) and content type, counted with the index; read with [channels]. */
    @Volatile var channelLangCounts: Map<String?, Int> = emptyMap(); private set
    @Volatile var channelKindCounts: Map<String, Int> = emptyMap(); private set

    private var rawVersionKeys: Map<Int, String> = emptyMap()
    private var rawQuality: Map<Int, Int> = emptyMap()
    private var rawSortKeys: Map<Int, String> = emptyMap()
    private var rawHd: Set<Int> = emptySet()

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
        override fun kind(ch: Channel): String? = chKind[ch.id]
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
        override fun kind(t: Movie): String? = vodCatKind[t.categoryId]
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
        override fun kind(t: Series): String? = seriesCatKind[t.categoryId]
        override fun id(t: Series): Int = t.id
        override fun category(t: Series): String = t.categoryId
    }

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
            guide?.ensureLoaded()
            shift(guide?.entries(id).orEmpty()).filter { it.end > nowSec }.take(10)
        }
        if (failed && list.isEmpty()) return null
        epgCache[id] = now to list
        CacheLimits.prune(epgCache, max = 800, drop = 200)
        return list
    }

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
    private val gridGate = kotlinx.coroutines.sync.Semaphore(DeviceTier.current.gridGuideRequests)

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
            shift(provider).ifEmpty {
                guide?.ensureLoaded()
                shift(guide?.entries(ch.id).orEmpty())
            }
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

    // ---------- Search ----------

    /** Names ready for Search to compare, per list; rebuilt by themselves when a list is replaced. */
    val searchChannels = NormalizedNames<Channel> { it.name }
    val searchMovies = NormalizedNames<Movie> { it.name }
    val searchSeries = NormalizedNames<Series> { it.name }

    fun clearGuideCache() {
        epgCache.clear()
        gridCache.clear()
        epgVersion++
    }

    private val rowGate = kotlinx.coroutines.sync.Semaphore(DeviceTier.current.rowGuideRequests)

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

    /** Programme covering a particular frame of live or rewound TV, using guide data already loaded. */
    fun programmeAt(id: Int, timeSec: Long): EpgEntry? {
        fun covers(entry: EpgEntry) = timeSec in entry.start until entry.end
        epgCache[id]?.second?.firstOrNull(::covers)?.let { return it }
        gridCache[id]?.second?.firstOrNull(::covers)?.let { return it }
        val offset = settings().epgOffsetMin * 60L
        val rawTime = timeSec - offset
        return guide?.entries(id)?.firstOrNull { rawTime in it.rawStart until it.rawEnd }
            ?.let { it.copy(start = it.rawStart + offset, end = it.rawEnd + offset) }
    }

    companion object {
        private val LABEL = mapOf("get_live_streams" to "channels", "get_vod_streams" to "movies", "get_series" to "series")
        const val PROVIDER_GUIDE = "provider"
        const val MAX_GUIDE_CHANNELS = 1500

        fun yearIn(name: String): String? = CatalogParser.yearIn(name)
    }
}

private val LANG_MAP = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
