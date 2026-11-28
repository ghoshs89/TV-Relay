package dev.relay.tv.data

import kotlinx.serialization.Serializable

/**
 * Learned per-title age certification and API genres, stored alongside learned languages (`learned_meta.json`,
 * capped at [MAX_SAVED] entries) so Kids age gating and Movies/Series genre chips keep working offline once
 * enriched from TMDB or MDBList.
 */
@Serializable
data class TitleMeta(
    val cert: String? = null,
    val genres: List<String> = emptyList(),
    val originalLang: String? = null,
    val dubbed: Boolean = false,
)

class LearnedMeta {
    private val map = LinkedHashMap<String, TitleMeta>(128, 0.75f, true)

    @Synchronized
    fun load(saved: Map<String, TitleMeta>) {
        map.clear()
        saved.entries.take(MAX_SAVED).forEach { (k, v) -> map[k] = v }
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun get(key: String): TitleMeta? = map[key]

    @Synchronized
    fun movie(id: Int): TitleMeta? = map["m:$id"]

    @Synchronized
    fun series(id: Int): TitleMeta? = map["s:$id"]

    /** Records metadata for [key]; returns true when anything persisted changed. */
    @Synchronized
    fun put(
        key: String,
        cert: String? = null,
        genres: List<String> = emptyList(),
        originalLang: String? = null,
        dubbed: Boolean? = null,
    ): Boolean {
        val prev = map[key]
        val next = TitleMeta(
            cert = cert?.takeIf { it.isNotBlank() } ?: prev?.cert,
            genres = genres.ifEmpty { prev?.genres.orEmpty() },
            originalLang = originalLang ?: prev?.originalLang,
            dubbed = dubbed ?: prev?.dubbed ?: false,
        )
        if (next.cert == null && next.genres.isEmpty() && next.originalLang == null && !next.dubbed) return false
        if (prev == next) return false
        map[key] = next
        trimTo(MAX_SAVED)
        return true
    }

    @Synchronized
    fun snapshot(max: Int = MAX_SAVED): Map<String, TitleMeta> =
        if (map.size <= max) LinkedHashMap(map)
        else {
            val out = LinkedHashMap<String, TitleMeta>(max)
            val drop = map.size - max
            var i = 0
            for ((k, v) in map) if (i++ >= drop) out[k] = v
            out
        }

    private fun trimTo(max: Int) {
        val it = map.iterator()
        while (map.size > max && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    companion object {
        const val MAX_SAVED = 5000
    }
}
