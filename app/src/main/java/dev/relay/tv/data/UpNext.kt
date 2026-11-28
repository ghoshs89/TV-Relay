package dev.relay.tv.data

/**
 * The next episode to watch for each series where the last episode watched was finished. Continue
 * watching keeps episodes in progress; Up next only ever holds the episode after a finished one, so the
 * two rows never show the same thing. A series removed from Up next stays away until it is played again.
 */
object UpNext {
    data class Item(
        val seriesId: Int,
        val episode: Episode,
        /** When the finished episode before it was last played. */
        val after: Long,
    ) {
        val key: String get() = "ep:${episode.id}"
    }

    /** Series to fetch episode lists for: the [max] most recently played eligible ones with a finished last episode. */
    fun seriesToLoad(
        history: List<WatchEntry>,
        max: Int = 8,
        dismissed: Map<String, Long> = emptyMap(),
        allowed: (Int) -> Boolean = { true },
    ): List<Int> =
        latestPerSeries(history)
            .filter { (sid, last) ->
                last.finished &&
                    allowed(sid) &&
                    !Dismissals.hidesUpNext(dismissed, sid, last.updated) &&
                    history.none { it.kind == "episode" && it.seriesId == sid && it.inProgress }
            }
            .take(max)
            .map { it.first }

    /**
     * Up next for [history] (one profile). [episodes] gives a series' episodes, or null when they are not
     * known yet; [dismissed] is the profile's dismissals; [allowed] filters out series the profile may not see.
     */
    fun build(
        history: List<WatchEntry>,
        episodes: (Int) -> List<Episode>?,
        dismissed: Map<String, Long>,
        allowed: (Int) -> Boolean = { true },
        max: Int = 20,
    ): List<Item> {
        val byKey = history.associateBy { it.key }
        val out = ArrayList<Item>()
        for ((sid, last) in latestPerSeries(history)) {
            if (!last.finished || !allowed(sid)) continue
            if (history.any { it.kind == "episode" && it.seriesId == sid && it.inProgress }) continue
            if (Dismissals.hidesUpNext(dismissed, sid, last.updated)) continue
            val all = ordered(episodes(sid) ?: continue)
            val at = all.indexOfFirst { "ep:${it.id}" == last.key }
            if (at < 0) continue
            val next = all.drop(at + 1).firstOrNull { byKey["ep:${it.id}"]?.finished != true } ?: continue
            out += Item(sid, next, last.updated)
            if (out.size >= max) break
        }
        return out
    }

    /** Episodes in watching order: by season, then number (season 0, specials, last). */
    fun ordered(all: List<Episode>): List<Episode> =
        all.sortedWith(compareBy<Episode>({ if (it.season <= 0) Int.MAX_VALUE else it.season }, { it.number }))

    /** The next episode after [current] in [all], or null at the end of what is known. */
    fun after(all: List<Episode>, current: String): Episode? {
        val list = ordered(all)
        val i = list.indexOfFirst { it.id == current }
        return if (i < 0) null else list.getOrNull(i + 1)
    }

    private fun latestPerSeries(history: List<WatchEntry>): List<Pair<Int, WatchEntry>> =
        history.asSequence()
            .filter { it.kind == "episode" && it.seriesId != null }
            .groupBy { it.seriesId!! }
            .map { (sid, list) -> sid to list.maxBy { it.updated } }
            .sortedByDescending { it.second.updated }
}
