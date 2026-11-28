package dev.relay.tv.data

import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

/**
 * Choosing a trailer. Two sources feed it: the provider's `youtube_trailer` field, which may hold
 * an id, a full address or junk, and TMDB's list of videos. Plain logic, so it is tested without a device.
 */
object Trailers {

    /**
     * Whether detail pages offer "Find a trailer" (a YouTube search) when no trailer is known. Whether the
     * YouTube app on a TV opens a search from an address was not checked on a TV before release: if it lands
     * on its home page instead, set this to false.
     */
    const val SEARCH_ENABLED = true

    /** One entry of TMDB's `videos.results`. */
    @kotlinx.serialization.Serializable
    data class Video(
        val site: String,
        val type: String,
        val official: Boolean,
        val language: String?,
        val key: String,
        val published: String?,
    )

    private val BARE = Regex("^[A-Za-z0-9_-]{11}$")

    // An 11 letter word such as "Documentary" fits the shape of an id but is not one. Real ids are
    // random, so one made only of lower case letters, one capital then lower case, only capitals or
    // only digits is far more likely a word or a number that a provider put in the wrong field.
    private val WORDLIKE = Regex("^(?:[A-Z]?[a-z]+|[A-Z]+|[0-9]+)$")

    private val IN_ADDRESS = Regex(
        "(?i)^(?:https?://)?(?:www\\.|m\\.|music\\.)?" +
            "(?:youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#]*&)?v=|embed/|shorts/|v/|live/)|youtu\\.be/)" +
            "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])",
    )

    /**
     * The 11 character YouTube id in [raw], which may be the id itself or a `watch?v=`, `youtu.be/`,
     * `embed/` or `shorts/` address. Anything else gives null.
     */
    fun youtubeId(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        IN_ADDRESS.find(s)?.let { return it.groupValues[1] }
        return s.takeIf { BARE.matches(it) && !WORDLIKE.matches(it) }
    }

    /** TMDB gives the id on its own; only its shape is checked. */
    private fun tmdbId(key: String): String? = key.trim().takeIf { BARE.matches(it) }

    private data class Ranked(val id: String, val type: Int, val official: Int, val language: Int, val published: String)

    /**
     * The id of the trailer to offer, or null. YouTube only; a Trailer before a Teaser and nothing
     * else; an official one first; then one in the title's own language, then English, then any;
     * the newest among equals. The provider's id is used when TMDB has nothing, and wins over TMDB's
     * pick when it is just as good by those rules.
     */
    fun pick(providerRaw: String?, tmdbVideos: List<Video>, titleLanguage: String?): String? {
        val own = youtubeId(providerRaw)
        val lang = titleLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val ranked = tmdbVideos.mapNotNull { v ->
            if (!v.site.equals("youtube", ignoreCase = true)) return@mapNotNull null
            val type = when {
                v.type.equals("trailer", ignoreCase = true) -> 0
                v.type.equals("teaser", ignoreCase = true) -> 1
                else -> return@mapNotNull null
            }
            val id = tmdbId(v.key) ?: return@mapNotNull null
            val l = v.language?.trim()?.lowercase()
            val languageRank = when {
                lang != null && l == lang -> 0
                l == "en" -> 1
                else -> 2
            }
            Ranked(id, type, if (v.official) 0 else 1, languageRank, v.published.orEmpty())
        }.sortedWith(
            compareBy<Ranked>({ it.type }, { it.official }, { it.language }).thenByDescending { it.published },
        )
        val top = ranked.firstOrNull() ?: return own
        if (own != null) {
            val same = ranked.firstOrNull { it.id == own }
            if (same != null && same.type == top.type && same.official == top.official && same.language == top.language) return own
        }
        return top.id
    }

    /** Reads `videos.results` from a TMDB answer (the whole title, or the `/videos` answer). */
    fun parse(videos: JsonElement?): List<Video> =
        videos.at("results").arr().orEmpty().mapNotNull { v ->
            val key = v.at("key").str() ?: return@mapNotNull null
            Video(
                site = v.at("site").str().orEmpty(),
                type = v.at("type").str().orEmpty(),
                official = v.at("official").flag(),
                language = v.at("iso_639_1").str(),
                key = key,
                published = v.at("published_at").str(),
            )
        }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** A YouTube search for the title, for when no trailer is known. */
    fun searchUrl(cleanTitle: String, year: String?): String {
        val y = year?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val words = listOfNotNull(cleanTitle.trim().ifEmpty { null }, y, "trailer").joinToString(" ")
        return "https://www.youtube.com/results?search_query=" + URLEncoder.encode(words, "UTF-8")
    }
}
