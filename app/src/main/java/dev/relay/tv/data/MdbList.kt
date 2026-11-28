package dev.relay.tv.data

import kotlinx.serialization.json.JsonElement

/** Reads MDBList responses. Kept free of I/O so it can be tested against recorded replies. */
object MdbList {
    const val BASE = "https://api.mdblist.com"

    sealed interface Reply {
        data class Found(
            val critics: Int?,
            val audience: Int?,
            val imdb: Double? = null,
            val certification: String? = null,
            val language: String? = null,
            val country: String? = null,
            val genres: List<String> = emptyList(),
        ) : Reply
        data object NotFound : Reply
        data object LimitReached : Reply
        data class Failed(val reason: String) : Reply
    }

    fun parseItem(code: Int, root: JsonElement?): Reply {
        if (code == 429) return Reply.LimitReached
        val err = root.at("error").str()
        if (root.at("response").str() == "false" || err != null) {
            val e = err.orEmpty()
            return when {
                e.contains("limit", ignoreCase = true) -> Reply.LimitReached
                e.contains("not found", ignoreCase = true) -> Reply.NotFound
                else -> Reply.Failed(e.ifBlank { "HTTP $code" })
            }
        }
        if (code == 404) return Reply.NotFound
        if (code !in 200..299) return Reply.Failed("HTTP $code")
        if (root == null) return Reply.Failed("Unreadable reply")
        val certification = root.at("certification").str()?.takeIf { it.isNotBlank() }
        val language = (root.at("language").str() ?: root.at("languages").arr()?.firstOrNull()?.str())?.takeIf { it.isNotBlank() }
        val country = (root.at("country").str() ?: root.at("countries").arr()?.firstOrNull()?.str())?.takeIf { it.isNotBlank() }
        val genres = root.at("genres").arr()?.mapNotNull { g ->
            (g.str() ?: g.at("title").str() ?: g.at("name").str())?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        val ratings = root.at("ratings").arr() ?: return Reply.Found(null, null, null, certification, language, country, genres)
        var critics: Int? = null
        var audience: Int? = null
        var imdb: Double? = null
        for (r in ratings) {
            val raw = r.at("value").dbl()
            val v = raw?.toInt()?.takeIf { it in 1..100 }
            when (r.at("source").str()?.lowercase()) {
                "tomatoes" -> critics = critics ?: v
                "popcorn", "tomatoesaudience" -> audience = audience ?: v
                "imdb" -> imdb = imdb ?: raw?.takeIf { it > 0.0 && it <= 10.0 }
            }
        }
        return Reply.Found(critics, audience, imdb, certification, language, country, genres)
    }

    /**
     * Picks the TMDB id from search results. A match must be the only candidate whose year is
     * within one of [year]; without a year, the only result whose title matches exactly. Anything
     * less certain returns null so no one else's score is shown.
     */
    fun pickSearch(root: JsonElement?, title: String, year: Int?): String? {
        val list = (root.at("search").arr() ?: root.at("results").arr() ?: root.arr()).orEmpty()
        val items = list.mapNotNull { e ->
            val id = e.at("ids").at("tmdb").str() ?: e.at("ids").at("tmdbid").str() ?: e.at("tmdbid").str() ?: e.at("tmdb_id").str()
            id?.takeIf { it.all(Char::isDigit) }?.let { Triple(it, e.at("year").int(), e.at("title").str().orEmpty()) }
        }
        val pick = if (year != null) {
            items.filter { (_, y, _) -> y != null && kotlin.math.abs(y - year) <= 1 }
        } else {
            items.filter { (_, _, t) -> norm(t) == norm(title) }
        }
        return pick.singleOrNull()?.first
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
