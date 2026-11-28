package dev.relay.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.ContentKinds
import dev.relay.tv.data.Episode
import dev.relay.tv.data.FavItem
import dev.relay.tv.data.KidsAge
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
 * plus Kids age-band rows and clickable cast filmographies. Worked out off the screen thread from the
 * library, the profile's history and (with a key) TMDB, and kept until any of those change.
 */
class Suggestions(private val app: RelayApp) {

    data class State(
        val stamp: String = "",
        val upNext: List<UpNext.Item> = emptyList(),
        val rows: Recommendations.Rows = Recommendations.Rows.EMPTY,
        val kidsByAge: Map<String, List<TitleRef>> = emptyMap(),
    )

    data class PersonMatches(
        val credits: Tmdb.PersonCredits?,
        val movies: List<TitleRef>,
        val series: List<TitleRef>,
        val hasTmdbKey: Boolean,
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

    /** Kids age band (6, 9, or 12) for a library title, or null if not kids-eligible. */
    fun kidsBandOf(t: TitleRef): Int? {
        val c = app.catalog
        val catName = if (t.tv) c.seriesCatName(t.categoryId) else c.vodCatName(t.categoryId)
        val meta = if (t.tv) c.seriesMeta(t.id) else c.movieMeta(t.id)
        val cached = app.tmdb.cached(t.tv, t.tmdbId, t.name)
        val cert = meta?.cert ?: cached?.certification
        val genres = meta?.genres?.ifEmpty { cached?.genres.orEmpty() } ?: cached?.genres.orEmpty()
        return KidsAge.classifyAgeBand(t.name, catName, certification = cert, genres = genres)
    }

    /** What the profile may see: adult category rules, hidden categories, and Kids age rating when on a kids profile. */
    private fun allowed(
        restrict: Boolean,
        hidden: Set<String>,
        kidsOnly: Boolean = false,
        kidsMaxAge: Int = KidsAge.DEFAULT_MAX_AGE,
    ): (TitleRef) -> Boolean {
        val r = app.catalog.restrictions(restrict)
        return { t ->
            r.allowsCategory(t.categoryId) &&
                (if (t.tv) "series:${t.categoryId}" else "vod:${t.categoryId}") !in hidden &&
                (!kidsOnly || (kidsBandOf(t)?.let { it <= kidsMaxAge } == true))
        }
    }

    private fun filterFor(
        pid: String,
        restrict: Boolean,
        hidden: Set<String>,
        kidsOnly: Boolean,
        kidsMaxAge: Int,
    ): Recommendations.Filter {
        val history = app.store.history(pid)
        return Recommendations.Filter(Recommendations.seen(history, ::ref), app.store.dismissed(pid), allowed(restrict, hidden, kidsOnly, kidsMaxAge))
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
        val profile = app.session.profile
        val kidsOnly = profile?.kids == true
        val kidsMaxAge = profile?.effectiveKidsAge() ?: KidsAge.DEFAULT_MAX_AGE
        val s = app.store.settings
        val restrict = app.session.restrict(s)
        val langs = app.session.langRank(s)
        val history = app.store.history(pid)
        val favs = app.store.favs(pid).filter { it.kind != "live" }
        val dismissed = app.store.dismissed(pid)
        val allowedTitle = allowed(restrict, s.hiddenCats, kidsOnly, kidsMaxAge)

        // Up next: fetch episode lists for recently finished eligible series that are missing or older than 12 hours.
        val allowSeriesId: (Int) -> Boolean = { sid -> ref(true, sid)?.let(allowedTitle) ?: false }
        loadEpisodes(UpNext.seriesToLoad(history, dismissed = dismissed, allowed = allowSeriesId))
        val upNext = UpNext.build(
            history, ::episodesOf, dismissed,
            allowed = allowSeriesId,
        )

        val seeds = Recommendations.seeds(history, favs, ::ref, allowed = allowedTitle)
        val filter = filterFor(pid, restrict, s.hiddenCats, kidsOnly, kidsMaxAge)
        val key = s.tmdbKey
        val kidsPool = if (kidsOnly) kidsCandidates(allowedTitle) else emptyList()
        val rows = if (key.isNotBlank() && seeds.isNotEmpty()) {
            val recs = coroutineScope {
                seeds.map { seed ->
                    async {
                        val id = Tmdb.validTmdbId(seed.title.tmdbId)
                            ?: app.tmdb.lookup(key, seed.title.tv, null, seed.title.name, seed.title.year)?.id
                        val list = id?.let { app.tmdb.recommendations(key, seed.title.tv, it) }.orEmpty()
                        val safe = if (kidsOnly) list.filter { KidsAge.isTmdbRecAllowedForKids(it) } else list
                        TitleVersions.groupKey(seed.title) to safe
                    }
                }.awaitAll().toMap()
            }
            val match = matcher(recs.values.flatten())
            val fromTmdb = Recommendations.fromTmdb(seeds, recs, match, filter, langs)
            if (fromTmdb.forYou.size >= 6) fromTmdb else merge(fromTmdb, local(seeds, filter, langs, kidsPool))
        } else {
            local(seeds, filter, langs, kidsPool)
        }
        val kidsByAge = if (kidsOnly) buildKidsByAge(kidsPool, langs, kidsMaxAge) else emptyMap()
        return State(stamp, upNext, rows, kidsByAge)
    }

    private fun local(
        seeds: List<Recommendations.Seed>,
        filter: Recommendations.Filter,
        langs: LangRank,
        extraPool: List<TitleRef>,
    ): Recommendations.Rows =
        Recommendations.local(seeds, (extraPool + pool(seeds)).distinctBy { it.key }, filter, langs, System.currentTimeMillis())

    /** Gather kids-eligible candidates across kids/family categories (not capped by overall top-1500). */
    private fun kidsCandidates(allow: (TitleRef) -> Boolean): List<TitleRef> {
        val c = app.catalog
        val out = ArrayList<TitleRef>(800)
        for (cat in c.vodCats) {
            if (cat.adult) continue
            val k = ContentKinds.ofTitleCategory(cat.name)
            if (k == ContentKinds.KIDS || KidsAge.classifyAgeBand("", cat.name) != null) {
                for (m in c.moviesByCat[cat.id].orEmpty().take(250)) {
                    val ref = TitleRef.of(m, c.movieLang(m.id), c.vodCatKindOf(m.categoryId))
                    if (allow(ref)) out += ref
                }
            }
        }
        for (cat in c.seriesCats) {
            if (cat.adult) continue
            val k = ContentKinds.ofTitleCategory(cat.name)
            if (k == ContentKinds.KIDS || KidsAge.classifyAgeBand("", cat.name) != null) {
                for (sr in c.seriesByCat[cat.id].orEmpty().take(250)) {
                    val ref = TitleRef.of(sr, c.seriesLang(sr.id), c.seriesCatKindOf(sr.categoryId))
                    if (allow(ref)) out += ref
                }
            }
        }
        for (m in c.moviesByAdded.take(600)) {
            val ref = TitleRef.of(m, c.movieLang(m.id), c.vodCatKindOf(m.categoryId))
            if (allow(ref)) out += ref
        }
        for (sr in c.seriesByAdded.take(400)) {
            val ref = TitleRef.of(sr, c.seriesLang(sr.id), c.seriesCatKindOf(sr.categoryId))
            if (allow(ref)) out += ref
        }
        return out.distinctBy { it.key }
    }

    private fun buildKidsByAge(
        kidsPool: List<TitleRef>,
        langs: LangRank,
        maxAge: Int,
    ): Map<String, List<TitleRef>> {
        val collapsed = TitleVersions.collapse(kidsPool, langs)
        val buckets = LinkedHashMap<String, MutableList<TitleRef>>()
        KidsAge.eligibleBands(maxAge).forEach { buckets[it] = ArrayList() }
        for (t in collapsed) {
            val age = kidsBandOf(t) ?: continue
            if (age > maxAge) continue
            buckets[KidsAge.bandOf(age)]?.add(t)
        }
        val out = LinkedHashMap<String, List<TitleRef>>()
        for ((band, items) in buckets) {
            if (items.isNotEmpty()) {
                out[band] = items
                    .sortedWith(compareBy<TitleRef> { langs.rank(it.lang) }.thenByDescending { it.rating }.thenByDescending { it.added })
                    .take(Recommendations.ROW)
            }
        }
        return out
    }

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
        val profile = app.session.profile
        val kidsOnly = profile?.kids == true
        val kidsMaxAge = profile?.effectiveKidsAge() ?: KidsAge.DEFAULT_MAX_AGE
        val s = app.store.settings
        val restrict = app.session.restrict(s)
        val filter = filterFor(pid, restrict, s.hiddenCats, kidsOnly, kidsMaxAge)
        val key = s.tmdbKey
        val tmdb = if (key.isBlank()) null else {
            val id = Tmdb.validTmdbId(title.tmdbId)
                ?: app.tmdb.lookup(key, title.tv, null, title.name, title.year)?.id
            val recs = id?.let { app.tmdb.recommendations(key, title.tv, it) }
            if (kidsOnly && recs != null) recs.filter { KidsAge.isTmdbRecAllowedForKids(it) } else recs
        }
        val match = matcher(tmdb.orEmpty())
        val c = app.catalog
        val candidates = if (title.tv) c.seriesByCat[title.categoryId].orEmpty().take(300).mapNotNull { ref(true, it.id) } +
            c.seriesByRating.take(400).mapNotNull { ref(true, it.id) }
        else c.moviesByCat[title.categoryId].orEmpty().take(300).mapNotNull { ref(false, it.id) } +
            c.moviesByRating.take(400).mapNotNull { ref(false, it.id) }
        val pool = if (kidsOnly) (candidates + kidsCandidates(allowed(restrict, s.hiddenCats, true, kidsMaxAge))).distinctBy { it.key } else candidates
        Recommendations.moreLikeThis(title, tmdb, match, pool, filter, app.session.langRank(s))
    }

    /** Movies and series in the library featuring [person], using TMDB person credits or cached TMDB metadata. */
    suspend fun titlesForPerson(person: Tmdb.Person): PersonMatches = withContext(Dispatchers.Default) {
        val s = app.store.settings
        val profile = app.session.profile
        val kidsOnly = profile?.kids == true
        val kidsMaxAge = profile?.effectiveKidsAge() ?: KidsAge.DEFAULT_MAX_AGE
        val restrict = app.session.restrict(s)
        val langs = app.session.langRank(s)
        val allow = allowed(restrict, s.hiddenCats, kidsOnly, kidsMaxAge)
        val hasKey = s.tmdbKey.isNotBlank()
        if (hasKey) {
            val credits = app.tmdb.personCredits(s.tmdbKey, person.id, person.name)
            if (credits != null) {
                val safe = if (kidsOnly) credits.credits.filter { KidsAge.isTmdbRecAllowedForKids(it) } else credits.credits
                val match = matcher(safe)
                val seenM = HashSet<String>()
                val seenS = HashSet<String>()
                val movies = ArrayList<TitleRef>()
                val series = ArrayList<TitleRef>()
                for (rec in safe) {
                    val hits = match(rec).filter { it.tv == rec.tv && allow(it) }
                    if (hits.isEmpty()) continue
                    val pick = TitleVersions.collapse(hits, langs).firstOrNull() ?: continue
                    val gk = TitleVersions.groupKey(pick)
                    if (rec.tv) {
                        if (seenS.add(gk)) series += pick
                    } else {
                        if (seenM.add(gk)) movies += pick
                    }
                }
                return@withContext PersonMatches(credits, movies, series, hasTmdbKey = true)
            }
        }
        // Fallback without a TMDB key: scan RAM-cached TMDB entries for cast matches.
        val c = app.catalog
        val mHits = ArrayList<TitleRef>()
        for (m in c.movies) {
            val info = app.tmdb.cached(false, m.tmdbId, m.name) ?: continue
            if (info.people.any { it.name.equals(person.name, true) }) {
                val r = TitleRef.of(m, c.movieLang(m.id), c.vodCatKindOf(m.categoryId))
                if (allow(r)) mHits += r
            }
        }
        val sHits = ArrayList<TitleRef>()
        for (sr in c.series) {
            val info = app.tmdb.cached(true, sr.tmdbId, sr.name) ?: continue
            if (info.people.any { it.name.equals(person.name, true) }) {
                val r = TitleRef.of(sr, c.seriesLang(sr.id), c.seriesCatKindOf(sr.categoryId))
                if (allow(r)) sHits += r
            }
        }
        PersonMatches(
            credits = null,
            movies = TitleVersions.collapse(mHits, langs).sortedByDescending { it.rating },
            series = TitleVersions.collapse(sHits, langs).sortedByDescending { it.rating },
            hasTmdbKey = hasKey,
        )
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
            val profile = app.session.profile
            val s = app.store.settings
            val histList = app.store.history(pid)
            val hist = histList.firstOrNull()?.let { "${it.key}@${it.updated}/${it.position}" }.orEmpty()
            val disMap = app.store.dismissed(pid)
            val dis = "${disMap.size}:${disMap.values.maxOrNull() ?: 0L}"
            return listOf(
                pid, hist, histList.size, favs.size, favs.firstOrNull()?.at ?: 0L, dis,
                app.catalog.libraryVersion, app.catalog.langVersion,
                s.tmdbKey.hashCode(), app.session.restrict(s),
                profile?.kids ?: false, profile?.effectiveKidsAge() ?: KidsAge.DEFAULT_MAX_AGE,
                s.hiddenCats.hashCode(), app.session.langOrder(s).joinToString(","),
            ).joinToString("|")
        }
    }
}
