package dev.relay.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Optional TMDB enrichment: better backdrops, ratings and cast when the provider's data is thin. */
class Tmdb(private val http: OkHttpClient) {

    data class Info(
        val overview: String?,
        val backdrop: String?,
        val poster: String?,
        val rating: Double?,
        val year: String?,
        val genres: List<String>,
        val cast: List<String>,
        val id: String? = null,
        /** Original language ("hi", "bn", "en", "ta"...) and first production country, for language groups. */
        val language: String? = null,
        val country: String? = null,
        /** TMDB's videos for the title (trailers, teasers, clips), see [Trailers.pick]. */
        val videos: List<Trailers.Video> = emptyList(),
    )

    private val cache = ConcurrentHashMap<String, Info>()

    suspend fun lookup(key: String, tv: Boolean, tmdbId: String?, title: String, year: String?): Info? =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext null
            val type = if (tv) "tv" else "movie"
            val cacheKey = "$type:${tmdbId ?: title}"
            cache[cacheKey]?.let { return@withContext it }
            try {
                val id = tmdbId?.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } } ?: run {
                    val b = "https://api.themoviedb.org/3/search/$type".toHttpUrl().newBuilder()
                        .addQueryParameter("query", cleanTitle(title))
                    if (year != null && year.length == 4) {
                        b.addQueryParameter(if (tv) "first_air_date_year" else "year", year)
                    }
                    get(b.build(), key).at("results").arr()?.firstOrNull().at("id").str()
                } ?: return@withContext null

                val d = get(
                    "https://api.themoviedb.org/3/$type/$id".toHttpUrl().newBuilder()
                        .addQueryParameter("append_to_response", "credits,videos")
                        .addQueryParameter("include_video_language", "en,null")
                        .build(),
                    key,
                )
                val language = d.at("original_language").str()
                var videos = Trailers.parse(d.at("videos"))
                if (language != null && language != "en" && videos.none { it.language.equals(language, ignoreCase = true) }) {
                    // Trailers in the film's own language are not part of the first answer; ask once more.
                    videos = videos + ownLanguageVideos(type, id, language, key)
                }
                val info = Info(
                    overview = d.at("overview").str(),
                    backdrop = d.at("backdrop_path").str()?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    poster = d.at("poster_path").str()?.let { "https://image.tmdb.org/t/p/w500$it" },
                    rating = d.at("vote_average").dbl()?.takeIf { it > 0 },
                    year = (d.at("release_date").str() ?: d.at("first_air_date").str())?.take(4),
                    genres = d.at("genres").arr()?.mapNotNull { it.at("name").str() } ?: emptyList(),
                    cast = d.at("credits").at("cast").arr()?.take(6)?.mapNotNull { it.at("name").str() } ?: emptyList(),
                    id = id,
                    language = language,
                    country = d.at("production_countries").arr()?.firstOrNull().at("iso_3166_1").str()
                        ?: d.at("origin_country").arr()?.firstOrNull().str(),
                    videos = videos,
                )
                cache[cacheKey] = info
                info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private fun ownLanguageVideos(type: String, id: String, language: String, key: String): List<Trailers.Video> =
        try {
            Trailers.parse(
                get(
                    "https://api.themoviedb.org/3/$type/$id/videos".toHttpUrl().newBuilder()
                        .addQueryParameter("include_video_language", language)
                        .build(),
                    key,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /** Asks TMDB whether it accepts [key]; tells a refused key from a service that could not be reached. */
    suspend fun check(key: String): KeyCheck = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext KeyCheck.Rejected
        try {
            val url = "https://api.themoviedb.org/3/configuration".toHttpUrl()
            http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
                .newCall(request(url, key)).execute().use { KeyText.tmdbCheck(it.code) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyCheck.Unreachable
        }
    }

    suspend fun validate(key: String): Boolean = check(key) == KeyCheck.Ok

    /** A v3 key goes in the address as `api_key`; a v4 read access token goes in the Authorization header. */
    private fun request(url: HttpUrl, key: String): Request =
        if (KeyText.kind(key) == KeyKind.V4) {
            Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        } else {
            Request.Builder().url(url.newBuilder().addQueryParameter("api_key", key).build()).build()
        }

    private fun get(url: HttpUrl, key: String): JsonElement =
        http.newCall(request(url, key)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("TMDB ${r.code}")
            Jsons.json.parseToJsonElement(r.body!!.string())
        }

    companion object {
        private val PREFIX = Regex("^\\s*[\\[(|]?\\s*[A-Z]{2,4}\\s*[\\])|]?\\s*[-:|]\\s+")
        private val TAGS = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|h265|multi|vostfr|sub|dub)\\b")
        private val YEAR = Regex("[(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]")

        fun cleanTitle(raw: String): String = TitleParts.parse(raw).title
            .replace(PREFIX, "")
            .replace(YEAR, "")
            .replace(TAGS, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
