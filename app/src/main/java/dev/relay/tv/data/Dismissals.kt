package dev.relay.tv.data

/**
 * What a profile asked not to see again, item key to when. Keys:
 * - `notint:<group>`: "Not interested" in a title (all its versions, see [TitleVersions.groupKey]).
 * - `upnext:<series id>`: removed from Up next; it comes back once the series is played after that time.
 * Synced as `dis:<profile>:<key>` records; older screens keep those records without reading them.
 */
object Dismissals {
    const val MAX = 500

    fun notInterested(group: String) = "notint:$group"
    fun upNext(seriesId: Int) = "upnext:$seriesId"

    /** [m] with [item] dismissed at [at], keeping the newest [MAX]. */
    fun add(m: Map<String, Long>, item: String, at: Long): Map<String, Long> {
        val next = m + (item to at)
        return if (next.size <= MAX) next else next.entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }
    }

    /** Both maps, the later time winning where both have an item. */
    fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        val out = HashMap(a)
        for ((k, v) in b) if ((out[k] ?: 0L) < v) out[k] = v
        return if (out.size <= MAX) out else out.entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }
    }

    /** True when the title group [group] was marked "Not interested". */
    fun isNotInterested(m: Map<String, Long>, group: String): Boolean = m.containsKey(notInterested(group))

    /** True when [seriesId] was removed from Up next after it was last played ([lastPlayed]). */
    fun hidesUpNext(m: Map<String, Long>, seriesId: Int, lastPlayed: Long): Boolean {
        val at = m[upNext(seriesId)] ?: return false
        return at >= lastPlayed
    }
}
