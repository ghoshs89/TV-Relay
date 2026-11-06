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
                        .addQueryParameter("api_key", key)
                        .addQueryParameter("query", cleanTitle(title))
                    if (year != null && year.length == 4) {
                        b.addQueryParameter(if (tv) "first_air_date_year" else "year", year)
                    }
                    get(b.build()).at("results").arr()?.firstOrNull().at("id").str()
                } ?: return@withContext null

                val d = get(
                    "https://api.themoviedb.org/3/$type/$id".toHttpUrl().newBuilder()
                        .addQueryParameter("api_key", key)
                        .addQueryParameter("append_to_response", "credits")
                        .build(),
                )
                val info = Info(
                    overview = d.at("overview").str(),
                    backdrop = d.at("backdrop_path").str()?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    poster = d.at("poster_path").str()?.let { "https://image.tmdb.org/t/p/w500$it" },
                    rating = d.at("vote_average").dbl()?.takeIf { it > 0 },
                    year = (d.at("release_date").str() ?: d.at("first_air_date").str())?.take(4),
                    genres = d.at("genres").arr()?.mapNotNull { it.at("name").str() } ?: emptyList(),
                    cast = d.at("credits").at("cast").arr()?.take(6)?.mapNotNull { it.at("name").str() } ?: emptyList(),
                )
                cache[cacheKey] = info
                info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            get("https://api.themoviedb.org/3/configuration".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key).build())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun get(url: HttpUrl): JsonElement =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("TMDB ${r.code}")
            Jsons.json.parseToJsonElement(r.body!!.string())
        }

    companion object {
        private val PREFIX = Regex("^\\s*[\\[(|]?\\s*[A-Z]{2,4}\\s*[\\])|]?\\s*[-:|]\\s+")
        private val TAGS = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|h265|multi|vostfr|sub|dub)\\b")
        private val YEAR = Regex("[(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]")

        fun cleanTitle(raw: String): String = raw
            .replace(PREFIX, "")
            .replace(YEAR, "")
            .replace(TAGS, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
