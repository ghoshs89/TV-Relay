package dev.relay.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Episode
import dev.relay.tv.data.FavItem
import dev.relay.tv.data.LangRank
import dev.relay.tv.data.Recommendations
import dev.relay.tv.data.SearchText
import dev.relay.tv.data.TitleRef
import dev.relay.tv.data.TitleVersions
import dev.relay.tv.data.Tmdb
import dev.relay.tv.data.UpNext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch next for the profile on screen: Up next, Recommended for you and "Because you watched" rows,
 * worked out off the screen thread from the library, the profile's history and (with a key) TMDB, and
 * kept until any of those change. The rules themselves are in [UpNext] and [Recommendations].
 */
class Suggestions(private val app: RelayApp) {

    data class State(
        val stamp: String = "",
        val upNext: List<UpNext.Item> = emptyList(),
        val rows: Recommendations.Rows = Recommendations.Rows.EMPTY,
    )

    var state by mutableStateOf(State())
        private set

    private var job: Job? = null

    /** Episode lists for Up next, by series, with when they were fetched. Fed by detail pages too. */
    private val episodes = ConcurrentHashMap<Int, Pair<Long, List<Episode>>>()

    fun rememberEpisodes(seriesId: Int, list: List<Episode>) {
        if (list.isNotEmpty()) episodes[seriesId] = System.currentTimeMillis() to list
    }

    fun episodesOf(seriesId: Int): List<Episode>? = episodes[seriesId]?.second

    /** A library title as the rules see it. */
    fun ref(tv: Boolean, id: Int): TitleRef? {
        val c = app.catalog
        return if (tv) c.seriesById[id]?.let { TitleRef.of(it, c.seriesLang(it.id), c.seriesCatKindOf(it.categoryId)) }
        else c.movieById[id]?.let { TitleRef.of(it, c.movieLang(it.id), c.vodCatKindOf(it.categoryId)) }
    }

    /** What the profile may see: kids and adult rules, and categories hidden in Settings. */
    private fun allowed(restrict: Boolean, hidden: Set<String>): (TitleRef) -> Boolean {
        val r = app.catalog.restrictions(restrict)
        return { t -> r.allowsCategory(t.categoryId) && (if (t.tv) "series:${t.categoryId}" else "vod:${t.categoryId}") !in hidden }
    }

    private fun filterFor(pid: String, restrict: Boolean, hidden: Set<String>): Recommendations.Filter {
        val history = app.store.history(pid)
        return Recommendations.Filter(Recommendations.seen(history, ::ref), app.store.dismissed(pid), allowed(restrict, hidden))
    }

    /**
     * Works the rows out again when [stamp] (profile, history, favourites, dismissals, library, key) has
     * changed since the last time. Cheap to call on every recomposition of Home.
     */
    fun refresh(stamp: String) {
        if (stamp == state.stamp && job?.isActive != true) return
        if (job?.isActive == true && pendingStamp == stamp) return
        job?.cancel()
        pendingStamp = stamp
        job = app.appScope.launch {
            try {
                val next = withContext(Dispatchers.Default) { compute(stamp) } ?: return@launch
                state = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Suggestions", "Couldn't work out Watch next", e)
            }
        }
    }

    @Volatile private var pendingStamp: String? = null

    private suspend fun compute(stamp: String): State? {
        val pid = app.session.profileId ?: return null
        if (app.catalog.movies.isEmpty() && app.catalog.series.isEmpty()) return State(stamp)
        val s = app.store.settings
        val restrict = app.session.restrict(s)
        val langs = app.session.langRank(s)
        val history = app.store.history(pid)
        val favs = app.store.favs(pid).filter { it.kind != "live" }
        val dismissed = app.store.dismissed(pid)
        val allowedSeries = allowed(restrict, s.hiddenCats)

        // Up next: fetch episode lists for recently finished eligible series that are missing or older than 12 hours.
        val allowSeriesId: (Int) -> Boolean = { sid -> ref(true, sid)?.let(allowedSeries) ?: false }
        loadEpisodes(UpNext.seriesToLoad(history, dismissed = dismissed, allowed = allowSeriesId))
        val upNext = UpNext.build(
            history, ::episodesOf, dismissed,
            allowed = allowSeriesId,
        )

        val seeds = Recommendations.seeds(history, favs, ::ref, allowed = allowedSeries)
        val filter = filterFor(pid, restrict, s.hiddenCats)
        val key = s.tmdbKey
        val rows = if (key.isNotBlank() && seeds.isNotEmpty()) {
            val recs = coroutineScope {
                seeds.map { seed ->
                    async {
                        val id = Tmdb.validTmdbId(seed.title.tmdbId)
                            ?: app.tmdb.lookup(key, seed.title.tv, null, seed.title.name, seed.title.year)?.id
                        TitleVersions.groupKey(seed.title) to (id?.let { app.tmdb.recommendations(key, seed.title.tv, it) }.orEmpty())
                    }
                }.awaitAll().toMap()
            }
            val match = matcher(recs.values.flatten())
            val fromTmdb = Recommendations.fromTmdb(seeds, recs, match, filter, langs)
            if (fromTmdb.forYou.size >= 6) fromTmdb else merge(fromTmdb, local(seeds, filter, langs))
        } else {
            local(seeds, filter, langs)
        }
        return State(stamp, upNext, rows)
    }

    private fun local(seeds: List<Recommendations.Seed>, filter: Recommendations.Filter, langs: LangRank): Recommendations.Rows =
        Recommendations.local(seeds, pool(seeds), filter, langs, System.currentTimeMillis())

    /** TMDB's rows first, topped up from the library's own ranking. */
    private fun merge(a: Recommendations.Rows, b: Recommendations.Rows): Recommendations.Rows {
        val forYou = (a.forYou + b.forYou).distinctBy { TitleVersions.groupKey(it.title) }.take(Recommendations.ROW)
        val because = (a.because + b.because).distinctBy { TitleVersions.groupKey(it.first.title) }.take(2)
        return Recommendations.Rows(forYou, because)
    }

    /**
     * The titles the library ranking looks at: recently added and top rated films and series, and the
     * seeds' own categories. Bounded, so a library of 166,000 films costs the same as a small one.
     */
    private fun pool(seeds: List<Recommendations.Seed>): List<TitleRef> {
        val c = app.catalog
        val out = ArrayList<TitleRef>(6000)
        fun m(list: List<dev.relay.tv.data.Movie>) = list.forEach { out += TitleRef.of(it, c.movieLang(it.id), c.vodCatKindOf(it.categoryId)) }
        fun s(list: List<dev.relay.tv.data.Series>) = list.forEach { out += TitleRef.of(it, c.seriesLang(it.id), c.seriesCatKindOf(it.categoryId)) }
        m(c.moviesByAdded.take(1500)); m(c.moviesByRating.take(1500))
        s(c.seriesByAdded.take(800)); s(c.seriesByRating.take(800))
        for (seed in seeds) {
            if (seed.title.tv) s(c.seriesByCat[seed.title.categoryId].orEmpty().take(400))
            else m(c.moviesByCat[seed.title.categoryId].orEmpty().take(400))
        }
        return out.distinctBy { it.key }
    }

    /**
     * Finds the library's copies of TMDB titles: by TMDB id and by cleaned title + year. Scans the
     * library directly with an anchor-word filter (longest word of each wanted title) so it never
     * allocates the Search index or runs O(library * recs) substring loops.
     */
    private fun matcher(recs: List<Tmdb.Rec>): (Tmdb.Rec) -> List<TitleRef> {
        if (recs.isEmpty()) return { emptyList() }
        val c = app.catalog
        val byId = HashMap<String, MutableList<TitleRef>>()
        val byName = HashMap<String, MutableList<TitleRef>>()

        fun scan(tv: Boolean, kindRecs: List<Tmdb.Rec>) {
            if (kindRecs.isEmpty()) return
            val prefix = if (tv) "s" else "m"
            val wantIds = kindRecs.mapNotNull { Tmdb.validTmdbId(it.id) }.toHashSet()
            val wantNames = HashSet<String>(kindRecs.size * 2)
            val anchors = HashSet<String>(kindRecs.size * 2)
            for (r in kindRecs) {
                val clean = SearchText.norm(Tmdb.cleanTitle(r.title))
                if (clean.length < 3) continue
                wantNames += clean
                val anchor = clean.split(' ').maxByOrNull { it.length } ?: continue
                if (anchor.length >= 2) anchors += anchor
            }
            val minLen = anchors.minOfOrNull { it.length } ?: 0
            val maxLen = anchors.maxOfOrNull { it.length } ?: 0
            val buf = StringBuilder(32)
            if (tv) {
                for (item in c.series) {
                    val tmdb = Tmdb.validTmdbId(item.tmdbId)
                    if (tmdb != null && tmdb in wantIds) {
                        ref(true, item.id)?.let { byId.getOrPut("$prefix:$tmdb") { ArrayList() }.add(it) }
                        continue
                    }
                    if (!SearchText.containsAnyWord(item.name, anchors, minLen, maxLen, buf)) continue
                    val clean = SearchText.norm(Tmdb.cleanTitle(item.name))
                    if (clean in wantNames) ref(true, item.id)?.let { byName.getOrPut("$prefix:$clean") { ArrayList() }.add(it) }
                }
            } else {
                for (item in c.movies) {
                    val tmdb = Tmdb.validTmdbId(item.tmdbId)
                    if (tmdb != null && tmdb in wantIds) {
                        ref(false, item.id)?.let { byId.getOrPut("$prefix:$tmdb") { ArrayList() }.add(it) }
                        continue
                    }
                    if (!SearchText.containsAnyWord(item.name, anchors, minLen, maxLen, buf)) continue
                    val clean = SearchText.norm(Tmdb.cleanTitle(item.name))
                    if (clean in wantNames) ref(false, item.id)?.let { byName.getOrPut("$prefix:$clean") { ArrayList() }.add(it) }
                }
            }
        }
        scan(false, recs.filter { !it.tv })
        scan(true, recs.filter { it.tv })
        return { rec ->
            val k = if (rec.tv) "s" else "m"
            val idHits = byId["$k:${rec.id}"].orEmpty()
            val nameHits = byName["$k:${SearchText.norm(Tmdb.cleanTitle(rec.title))}"].orEmpty()
                .filter { t -> rec.year == null || t.year == null || t.year.take(4) == rec.year }
            when {
                nameHits.isEmpty() -> idHits
                idHits.isEmpty() -> nameHits
                else -> (idHits + nameHits).distinctBy { it.id }
            }
        }
    }

    private suspend fun loadEpisodes(series: List<Int>) {
        val now = System.currentTimeMillis()
        val due = series.filter { sid -> episodes[sid]?.let { now - it.first > 12 * 3_600_000L } ?: true }
        if (due.isEmpty()) return
        val gate = Semaphore(2)
        coroutineScope {
            due.map { sid ->
                async {
                    gate.withPermit {
                        try {
                            val info = app.api.seriesInfo(sid)
                            rememberEpisodes(sid, info.seasons.values.flatten())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.d("Suggestions", "Episodes for series $sid not loaded: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** "More like this" for an open film or series. */
    suspend fun moreLikeThis(title: TitleRef): List<TitleRef> = withContext(Dispatchers.Default) {
        val pid = app.session.profileId ?: return@withContext emptyList()
        val s = app.store.settings
        val restrict = app.session.restrict(s)
        val filter = filterFor(pid, restrict, s.hiddenCats)
        val key = s.tmdbKey
        val tmdb = if (key.isBlank()) null else {
            val id = Tmdb.validTmdbId(title.tmdbId)
                ?: app.tmdb.lookup(key, title.tv, null, title.name, title.year)?.id
            id?.let { app.tmdb.recommendations(key, title.tv, it) }
        }
        val match = matcher(tmdb.orEmpty())
        val c = app.catalog
        val candidates = if (title.tv) c.seriesByCat[title.categoryId].orEmpty().take(300).mapNotNull { ref(true, it.id) } +
            c.seriesByRating.take(400).mapNotNull { ref(true, it.id) }
        else c.moviesByCat[title.categoryId].orEmpty().take(300).mapNotNull { ref(false, it.id) } +
            c.moviesByRating.take(400).mapNotNull { ref(false, it.id) }
        Recommendations.moreLikeThis(title, tmdb, match, candidates, filter, app.session.langRank(s))
    }

    /** Hides [t] (every copy) from this profile's recommendations. */
    fun notInterested(t: TitleRef) {
        val pid = app.session.profileId ?: return
        app.store.dismiss(pid, dev.relay.tv.data.Dismissals.notInterested(TitleVersions.groupKey(t)))
    }

    /** Removes a series from this profile's Up next until it is played again. */
    fun hideUpNext(seriesId: Int) {
        val pid = app.session.profileId ?: return
        app.store.dismiss(pid, dev.relay.tv.data.Dismissals.upNext(seriesId))
    }

    companion object {
        /** Everything the rows depend on, as one string. */
        fun stamp(app: RelayApp, favs: List<FavItem>): String {
            val pid = app.session.profileId.orEmpty()
            val s = app.store.settings
            val histList = app.store.history(pid)
            val hist = histList.firstOrNull()?.let { "${it.key}@${it.updated}/${it.position}" }.orEmpty()
            val disMap = app.store.dismissed(pid)
            val dis = "${disMap.size}:${disMap.values.maxOrNull() ?: 0L}"
            return listOf(
                pid, hist, histList.size, favs.size, favs.firstOrNull()?.at ?: 0L, dis,
                app.catalog.libraryVersion, s.tmdbKey.hashCode(), app.session.restrict(s), s.hiddenCats.hashCode(),
                app.session.langOrder(s).joinToString(","),
            ).joinToString("|")
        }
    }
}
