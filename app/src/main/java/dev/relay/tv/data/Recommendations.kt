package dev.relay.tv.data

/**
 * What to watch next, per profile, from what that profile has watched: "Recommended for you", up to two
 * "Because you watched …" rows, and "More like this" on a detail page. Only titles the provider actually
 * has are offered. With a TMDB key, TMDB's recommendations for the profile's recent titles are matched to
 * the library; without one, the library is ranked by type, language, year, rating and when it was added.
 * Always leaves out what the profile has watched or started (any copy), titles it may not see, and titles
 * marked "Not interested". Pure: the caller hands in history, candidates and TMDB answers.
 */
object Recommendations {

    /** A title the profile watched, that recommendations are based on. [weight] is higher for recent ones. */
    data class Seed(val title: TitleRef, val weight: Double, val at: Long) {
        val name: String get() = Tmdb.cleanTitle(title.name).ifBlank { title.name }
    }

    /** A recommended title and why. */
    data class Pick(val title: TitleRef, val reason: String, val score: Double)

    data class Rows(val forYou: List<Pick>, val because: List<Pair<Seed, List<Pick>>>) {
        companion object {
            val EMPTY = Rows(emptyList(), emptyList())
        }
    }

    const val MAX_SEEDS = 5
    const val ROW = 20

    /**
     * The profile's seeds: finished films and series with at least one finished episode, most recent
     * first, plus titles on My list. [lookup] turns a history or favourite entry into a library title.
     */
    fun seeds(
        history: List<WatchEntry>,
        myList: List<FavItem>,
        lookup: (tv: Boolean, id: Int) -> TitleRef?,
        max: Int = MAX_SEEDS,
    ): List<Seed> {
        val out = LinkedHashMap<String, Pair<TitleRef, Long>>()
        for (e in history.sortedByDescending { it.updated }) {
            val ref = when (e.kind) {
                "movie" -> if (e.finished || e.position >= e.duration * 0.6 && e.duration > 0) e.streamId.toIntOrNull()?.let { lookup(false, it) } else null
                "episode" -> if (e.finished) e.seriesId?.let { lookup(true, it) } else null
                else -> null
            } ?: continue
            val g = TitleVersions.groupKey(ref)
            if (g !in out) out[g] = ref to e.updated
        }
        for (f in myList.sortedByDescending { it.at }) {
            val ref = when (f.kind) {
                "movie" -> lookup(false, f.id)
                "series" -> lookup(true, f.id)
                else -> null
            } ?: continue
            val g = TitleVersions.groupKey(ref)
            if (g !in out) out[g] = ref to f.at
        }
        return out.values.take(max).mapIndexed { i, (ref, at) -> Seed(ref, 1.0 / (1 + i * 0.5), at) }
    }

    /** Title groups the profile has watched or started, which are never recommended. */
    fun seen(history: List<WatchEntry>, lookup: (tv: Boolean, id: Int) -> TitleRef?): Set<String> {
        val out = HashSet<String>()
        for (e in history) {
            val ref = when (e.kind) {
                "movie" -> e.streamId.toIntOrNull()?.let { lookup(false, it) }
                "episode" -> e.seriesId?.let { lookup(true, it) }
                else -> null
            } ?: continue
            out += TitleVersions.groupKey(ref)
        }
        return out
    }

    /** Rules every recommendation follows. */
    class Filter(
        private val seen: Set<String>,
        private val dismissed: Map<String, Long>,
        private val allowed: (TitleRef) -> Boolean,
    ) {
        fun ok(t: TitleRef, group: String = TitleVersions.groupKey(t)): Boolean =
            group !in seen && !Dismissals.isNotInterested(dismissed, group) && allowed(t)
    }

    /**
     * With a TMDB key. [recs] is TMDB's answer for each seed (by seed group key); [match] finds the
     * library's copies of a recommended TMDB title. Scores add up across seeds, so a title several
     * of the viewer's titles point to comes first.
     */
    fun fromTmdb(
        seeds: List<Seed>,
        recs: Map<String, List<Tmdb.Rec>>,
        match: (Tmdb.Rec) -> List<TitleRef>,
        filter: Filter,
        langs: LangRank,
    ): Rows {
        val score = HashMap<String, Double>()
        val best = HashMap<String, TitleRef>()
        val bestSeed = HashMap<String, Pair<Seed, Double>>()
        val perSeed = LinkedHashMap<Seed, MutableList<Pair<TitleRef, Double>>>()
        for (seed in seeds) {
            val list = recs[TitleVersions.groupKey(seed.title)].orEmpty()
            for ((pos, r) in list.withIndex()) {
                val copies = match(r).filter { it.tv == r.tv }
                if (copies.isEmpty()) continue
                val pick = TitleVersions.collapse(copies, langs).first()
                val g = TitleVersions.groupKey(pick)
                if (!filter.ok(pick, g)) continue
                val s = seed.weight * (1.0 - pos / 25.0).coerceAtLeast(0.1)
                score[g] = (score[g] ?: 0.0) + s
                best[g] = pick
                if ((bestSeed[g]?.second ?: -1.0) < s) bestSeed[g] = seed to s
                perSeed.getOrPut(seed) { ArrayList() }.add(pick to s)
            }
        }
        val forYou = score.entries
            .map { (g, s) -> val t = best[g]!!; Pick(t, because(bestSeed[g]!!.first), s + bonus(t, langs)) }
            .sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.title.name })
            .take(ROW)
        return Rows(forYou, becauseRows(perSeed, langs))
    }

    /**
     * Without a TMDB key: ranks [candidates] (a bounded pool: recent and top-rated titles and the seeds'
     * own categories) by how much each looks like the seeds. With no seeds, the best of the viewer's
     * first language.
     */
    fun local(seeds: List<Seed>, candidates: List<TitleRef>, filter: Filter, langs: LangRank, now: Long): Rows {
        val pool = TitleVersions.collapse(candidates, langs).filter { filter.ok(it) }
        if (seeds.isEmpty()) {
            val first = langs.order.firstOrNull()
            val picks = pool
                .map { Pick(it, if (first != null && it.lang == first) "Top pick in ${Langs.label(first)}" else "Popular in your library", bonus(it, langs) + fresh(it, now)) }
                .sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.title.name })
                .take(ROW)
            return Rows(picks, emptyList())
        }
        val perSeed = LinkedHashMap<Seed, MutableList<Pair<TitleRef, Double>>>()
        val picks = ArrayList<Pick>()
        for (t in pool) {
            var total = 0.0
            var top: Pair<Seed, Double>? = null
            for (seed in seeds) {
                val s = seed.weight * likeness(seed.title, t)
                if (s <= 0) continue
                total += s
                if ((top?.second ?: 0.0) < s) top = seed to s
                if (s >= seed.weight * 1.0) perSeed.getOrPut(seed) { ArrayList() }.add(t to s)
            }
            if (top == null) continue
            picks += Pick(t, because(top.first), total + bonus(t, langs) + fresh(t, now))
        }
        val forYou = picks.sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.title.name }).take(ROW)
        return Rows(forYou, becauseRows(perSeed, langs))
    }

    /** "More like this" for an open title: TMDB's list when there is one, otherwise the library's look-alikes. */
    fun moreLikeThis(
        title: TitleRef,
        tmdb: List<Tmdb.Rec>?,
        match: (Tmdb.Rec) -> List<TitleRef>,
        candidates: List<TitleRef>,
        filter: Filter,
        langs: LangRank,
    ): List<TitleRef> {
        val own = TitleVersions.groupKey(title)
        val fromTmdb = tmdb.orEmpty().flatMap { r -> match(r).filter { it.tv == r.tv }.let { c -> if (c.isEmpty()) emptyList() else listOf(TitleVersions.collapse(c, langs).first()) } }
        val picked = if (fromTmdb.size >= 4) fromTmdb
        else (fromTmdb + TitleVersions.collapse(candidates, langs)
            .filter { it.tv == title.tv && likeness(title, it) > 0 }
            .sortedWith(compareByDescending<TitleRef> { likeness(title, it) + bonus(it, langs) }.thenBy { it.name }))
        return picked.distinctBy { TitleVersions.groupKey(it) }
            .filter { val g = TitleVersions.groupKey(it); g != own && filter.ok(it, g) }
            .take(ROW)
    }

    /** How much [t] looks like [seed], from 0: same type, same category, same language, close in years. */
    fun likeness(seed: TitleRef, t: TitleRef): Double {
        if (t.tv != seed.tv && seed.kind == null) return 0.0
        var s = 0.0
        if (seed.kind != null && t.kind == seed.kind) s += 1.0
        if (t.categoryId == seed.categoryId) s += 0.6
        if (seed.lang != null && t.lang == seed.lang) s += 0.5
        val ys = seed.year?.take(4)?.toIntOrNull()
        val yt = t.year?.take(4)?.toIntOrNull()
        if (ys != null && yt != null && kotlin.math.abs(ys - yt) <= 5) s += 0.2
        if (t.tv == seed.tv) s += 0.1
        return if (s >= 1.0) s else 0.0
    }

    private fun because(seed: Seed) = "Because you watched ${seed.name}"

    /** Small nudges: the viewer's languages first, then better rated titles. */
    private fun bonus(t: TitleRef, langs: LangRank): Double {
        val r = langs.rank(t.lang)
        val lang = if (r < langs.order.size) 0.3 * (1.0 - r.toDouble() / (langs.order.size + 1)) else 0.0
        return lang + (t.rating.coerceIn(0.0, 10.0) / 10.0) * 0.2
    }

    /** A little extra for titles added in the last 60 days. */
    private fun fresh(t: TitleRef, now: Long): Double {
        val ageDays = (now / 1000 - t.added) / 86_400.0
        return if (t.added > 0 && ageDays in 0.0..60.0) 0.15 * (1 - ageDays / 60.0) else 0.0
    }

    /** Up to two "Because you watched" rows, for the seeds with the most picks of their own. */
    private fun becauseRows(perSeed: Map<Seed, List<Pair<TitleRef, Double>>>, langs: LangRank): List<Pair<Seed, List<Pick>>> =
        perSeed.entries
            .map { (seed, list) ->
                seed to list.sortedWith(compareByDescending<Pair<TitleRef, Double>> { it.second + bonus(it.first, langs) }.thenBy { it.first.name })
                    .distinctBy { TitleVersions.groupKey(it.first) }
                    .take(ROW)
                    .map { Pick(it.first, because(seed), it.second) }
            }
            .filter { it.second.size >= 4 }
            .sortedByDescending { it.first.weight }
            .take(2)
}
