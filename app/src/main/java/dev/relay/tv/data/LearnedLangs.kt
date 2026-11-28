package dev.relay.tv.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Original languages learned from TMDB when a title is opened, kept by title number so that looking one
 * up while a list of 200,000 titles is built makes no new text. Saved as "m:<id>" and "s:<id>" keys.
 */
class LearnedLangs {
    enum class Change {
        /** Already known as this. */
        None,

        /** New to the saved list, but it is what Relay had guessed, so nothing on screen changes. */
        Confirmed,

        /** The title now belongs to another language group. */
        Moved,
    }

    private val movies = ConcurrentHashMap<Int, String>()
    private val series = ConcurrentHashMap<Int, String>()

    fun movie(id: Int): String? = movies[id]
    fun series(id: Int): String? = series[id]
    val size: Int get() = movies.size + series.size

    fun clear() {
        movies.clear()
        series.clear()
    }

    fun load(saved: Map<String, String>) {
        for ((k, v) in saved) {
            val (movie, id) = parse(k) ?: continue
            (if (movie) movies else series)[id] = v
        }
    }

    /** Records [group] for [key] ("m:12" or "s:12"). [guess] is what Relay shows for the title without it. */
    fun learn(key: String, group: String, guess: (movie: Boolean, id: Int) -> String?): Change {
        val (movie, id) = parse(key) ?: return Change.None
        val before = (if (movie) movies else series).put(id, group)
        if (before == group) return Change.None
        return if ((before ?: guess(movie, id)) == group) Change.Confirmed else Change.Moved
    }

    /** What to save: every entry, or [max] of them when there are more (so the file can't grow without limit). */
    fun snapshot(max: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((id, g) in movies) { if (out.size >= max) return out; out["m:$id"] = g }
        for ((id, g) in series) { if (out.size >= max) return out; out["s:$id"] = g }
        return out
    }

    private fun parse(key: String): Pair<Boolean, Int>? {
        if (key.length < 3 || key[1] != ':') return null
        val id = key.substring(2).toIntOrNull() ?: return null
        return when (key[0]) {
            'm' -> true to id
            's' -> false to id
            else -> null
        }
    }

    companion object {
        const val MAX_SAVED = 5000
    }
}
