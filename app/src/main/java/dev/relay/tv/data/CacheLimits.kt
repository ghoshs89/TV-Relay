package dev.relay.tv.data

import java.util.concurrent.ConcurrentHashMap

/** Keeps the in-memory guide caches from growing without limit while someone scrolls a 40,000 channel list. */
object CacheLimits {
    /** When [map] holds more than [max] entries, removes the [drop] oldest by timestamp. */
    fun <V> prune(map: ConcurrentHashMap<Int, Pair<Long, V>>, max: Int, drop: Int) {
        if (map.size <= max) return
        map.entries.sortedBy { it.value.first }.take(drop).forEach { map.remove(it.key, it.value) }
    }
}

/**
 * Remembers URLs that failed to load (channel logos are often dead links) so lists don't ask for
 * them again on every scroll past. A failure is forgotten after [retryMs] in case it was the network.
 */
class FailureMemory(
    private val max: Int = 2000,
    private val retryMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onGrew: (Int) -> Unit = {},
) {
    private val failed = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > max
    }

    @Synchronized
    fun has(url: String): Boolean {
        val at = failed[url] ?: return false
        if (clock() - at > retryMs) {
            failed.remove(url)
            return false
        }
        return true
    }

    @Synchronized
    fun add(url: String) {
        val isNew = failed.put(url, clock()) == null
        if (isNew) onGrew(failed.size)
    }

    @get:Synchronized
    val size: Int get() = failed.size
}
