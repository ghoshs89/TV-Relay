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

/**
 * Optional TMDB enrichment: better backdrops, title logos, ratings, cast and recommendations when the
 * provider's data is thin. Answers are kept in memory and, when [diskDir] is given, on disk for 7 days,
 * so the home hero and detail pages show them at once on the next start.
 */
class Tmdb(private val http: OkHttpClient, diskDir: java.io.File? = null) {

    /** A cast member with their photo (w185) and TMDB id, when TMDB has one. */
    @kotlinx.serialization.Serializable
    data class Person(val name: String, val character: String? = null, val photo: String? = null, val id: String? = null)

    /** A cast member's bio and combined filmography from TMDB. */
    @kotlinx.serialization.Serializable
    data class PersonCredits(
        val id: String,
        val name: String,
        val photo: String? = null,
        val bio: String? = null,
        val knownFor: String? = null,
        val credits: List<Rec> = emptyList(),
    )

    /** A title TMDB recommends (or a person credit); [tv] tells a series from a film. */
    @kotlinx.serialization.Serializable
    data class Rec(
        val id: String,
        val tv: Boolean,
        val title: String,
        val year: String? = null,
        val rating: Double? = null,
        val adult: Boolean = false,
        val genreIds: List<Int> = emptyList(),
    )

    @kotlinx.serialization.Serializable
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
        /** The title's logo (w500 PNG), in the title's own language, English or without text. */
        val logo: String? = null,
        /** Cast with photos; [cast] keeps the names alone. */
        val people: List<Person> = emptyList(),
        /** Age certification ("G", "PG", "PG-13", "TV-Y7", "R"...), parsed from release_dates / content_ratings. */
        val certification: String? = null,
    )

    private val cache = ConcurrentHashMap<String, Info>()
    private val recCache = ConcurrentHashMap<String, List<Rec>>()
    private val personCache = ConcurrentHashMap<String, PersonCredits>()
    private val personNameToId = ConcurrentHashMap<String, String>()
    private val disk = diskDir?.let { TmdbDiskCache(it) }

    private fun cacheKey(tv: Boolean, tmdbId: String?, title: String): String =
        "${if (tv) "tv" else "movie"}:${validTmdbId(tmdbId) ?: title}"

    /** What is already in RAM for a title (safe on the main thread); null when not yet loaded. */
    fun cached(tv: Boolean, tmdbId: String?, title: String): Info? = cache[cacheKey(tv, tmdbId, title)]

    /** Cached filmography for a cast member (safe on the main thread). */
    fun cachedPerson(personId: String?, name: String): PersonCredits? {
        val id = validTmdbId(personId) ?: personNameToId[TitleVersions.normalize(name)] ?: return null
        return personCache["person:$id"]
    }

    /** What is already known about a title without asking TMDB (RAM, then 7-day disk cache on IO). */
    suspend fun cachedOrDisk(tv: Boolean, tmdbId: String?, title: String): Info? {
        val key = cacheKey(tv, tmdbId, title)
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            disk?.read(key, Info.serializer())?.also {
                cache[key] = it
                CacheLimits.capSize(cache, max = 500, drop = 100)
            }
        }
    }

    suspend fun lookup(key: String, tv: Boolean, tmdbId: String?, title: String, year: String?): Info? =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext null
            val type = if (tv) "tv" else "movie"
            val cleanId = validTmdbId(tmdbId)
            val cacheKey = "$type:${cleanId ?: title}"
            cache[cacheKey]?.let { return@withContext it }
            disk?.read(cacheKey, Info.serializer())?.let {
                cache[cacheKey] = it
                return@withContext it
            }
            try {
                val id = cleanId ?: run {
                    val b = "https://api.themoviedb.org/3/search/$type".toHttpUrl().newBuilder()
                        .addQueryParameter("query", cleanTitle(title))
                    if (year != null && year.length == 4) {
                        b.addQueryParameter(if (tv) "first_air_date_year" else "year", year)
                    }
                    get(b.build(), key).at("results").arr()?.firstOrNull().at("id").str()
                } ?: return@withContext null

                val certSection = if (tv) "content_ratings" else "release_dates"
                val d = get(
                    "https://api.themoviedb.org/3/$type/$id".toHttpUrl().newBuilder()
                        .addQueryParameter("append_to_response", "credits,videos,images,$certSection")
                        .addQueryParameter("include_video_language", "en,null")
                        .addQueryParameter("include_image_language", LOGO_LANGUAGES)
                        .build(),
                    key,
                )
                val language = d.at("original_language").str()
                val people = d.at("credits").at("cast").arr()?.take(12)?.mapNotNull { c ->
                    val n = c.at("name").str() ?: return@mapNotNull null
                    Person(
                        name = n,
                        character = c.at("character").str()?.takeIf { it.isNotBlank() },
                        photo = c.at("profile_path").str()?.let { "https://image.tmdb.org/t/p/w185$it" },
                        id = c.at("id").str()?.takeIf { it.isNotEmpty() },
                    )
                }.orEmpty()
                var videos = Trailers.parse(d.at("videos"))
                if (language != null && language != "en" && videos.none { it.language.equals(language, ignoreCase = true) }) {
                    // Trailers in the film's own language are not part of the first answer; ask once more.
                    videos = videos + ownLanguageVideos(type, id, language, key)
                }
                var logos = d.at("images").at("logos").arr().orEmpty()
                if (language != null && language !in DEFAULT_LOGO_LANGS && logos.none { it.at("iso_639_1").str() == language }) {
                    logos = logos + ownLanguageLogos(type, id, language, key)
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
                    logo = pickLogo(logos, language)?.let { "https://image.tmdb.org/t/p/w500$it" },
                    people = people,
                    certification = parseCertification(d, tv),
                )
                cache[cacheKey] = info
                disk?.write(cacheKey, info, Info.serializer())
                CacheLimits.capSize(cache, max = 500, drop = 100)
                info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Titles TMDB recommends for the title [tmdbId] (a film unless [tv]), at most 20, newest knowledge
     * first. Kept for 7 days. Empty without a key, on any failure, or when TMDB has none.
     */
    suspend fun recommendations(key: String, tv: Boolean, tmdbId: String): List<Rec> = withContext(Dispatchers.IO) {
        val cleanId = validTmdbId(tmdbId) ?: return@withContext emptyList()
        if (key.isBlank()) return@withContext emptyList()
        val type = if (tv) "tv" else "movie"
        val cacheKey = "rec:$type:$cleanId"
        recCache[cacheKey]?.let { return@withContext it }
        disk?.read(cacheKey, kotlinx.serialization.builtins.ListSerializer(Rec.serializer()))?.let {
            recCache[cacheKey] = it
            return@withContext it
        }
        try {
            val d = get("https://api.themoviedb.org/3/$type/$cleanId/recommendations".toHttpUrl(), key)
            val recs = parseRecs(d, tv)
            recCache[cacheKey] = recs
            CacheLimits.capSize(recCache, max = 200, drop = 50)
            disk?.write(cacheKey, recs, kotlinx.serialization.builtins.ListSerializer(Rec.serializer()))
            recs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Bio and combined filmography (movies + series, acting roles first) for a cast member, cached for 7 days.
     * Uses [personId] directly when available, or resolves via TMDB person search by [name].
     */
    suspend fun personCredits(key: String, personId: String?, name: String): PersonCredits? =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext null
            val normName = TitleVersions.normalize(name)
            val resolvedId = validTmdbId(personId)
                ?: personNameToId[normName]
                ?: try {
                    val q = cleanTitle(name).ifBlank { name.trim() }
                    val url = "https://api.themoviedb.org/3/search/person".toHttpUrl().newBuilder()
                        .addQueryParameter("query", q)
                        .build()
                    get(url, key).at("results").arr()?.firstOrNull().at("id").str()?.also { id ->
                        if (normName.isNotEmpty() && validTmdbId(id) != null) personNameToId[normName] = id
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            val cleanId = validTmdbId(resolvedId) ?: return@withContext null
            val cacheKey = "person:$cleanId"
            personCache[cacheKey]?.let { return@withContext it }
            disk?.read(cacheKey, PersonCredits.serializer())?.let {
                personCache[cacheKey] = it
                return@withContext it
            }
            try {
                val url = "https://api.themoviedb.org/3/person/$cleanId".toHttpUrl().newBuilder()
                    .addQueryParameter("append_to_response", "combined_credits")
                    .build()
                val d = get(url, key)
                val credits = parsePersonCredits(cleanId, d)
                personCache[cacheKey] = credits
                if (normName.isNotEmpty()) personNameToId[normName] = cleanId
                CacheLimits.capSize(personCache, max = 100, drop = 25)
                disk?.write(cacheKey, credits, PersonCredits.serializer())
                credits
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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

    private fun ownLanguageLogos(type: String, id: String, language: String, key: String): List<JsonElement> =
        try {
            get(
                "https://api.themoviedb.org/3/$type/$id/images".toHttpUrl().newBuilder()
                    .addQueryParameter("include_image_language", language)
                    .build(),
                key,
            ).at("logos").arr().orEmpty()
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
        private val DEFAULT_LOGO_LANGS = setOf("en", "hi", "bn", "ta", "te", "ml", "kn")
        private const val LOGO_LANGUAGES = "en,hi,bn,ta,te,ml,kn,null"
        private val CERT_COUNTRIES = listOf("US", "GB", "IN", "CA", "AU")
        private val PREFIX = Regex("^\\s*[\\[(|]?\\s*[A-Z]{2,4}\\s*[\\])|]?\\s*[-:|]\\s+")
        private val TAGS = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|h265|multi|vostfr|sub|dub)\\b")
        private val YEAR = Regex("[(\\[]\\s*(19|20)\\d{2}\\s*[)\\]]")

        /** Positive numeric TMDB id, or null when empty or a provider placeholder like "0". */
        fun validTmdbId(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }

        /** Parses US -> GB -> IN -> CA -> AU -> any age certification from release_dates or content_ratings. */
        internal fun parseCertification(d: JsonElement, tv: Boolean): String? {
            if (tv) {
                val list = d.at("content_ratings").at("results").arr().orEmpty()
                for (cc in CERT_COUNTRIES) {
                    list.firstOrNull { it.at("iso_3166_1").str().equals(cc, true) }
                        ?.at("rating").str()?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return list.firstNotNullOfOrNull { it.at("rating").str()?.takeIf { r -> r.isNotBlank() } }
            }
            val list = d.at("release_dates").at("results").arr().orEmpty()
            fun fromCountry(entry: JsonElement): String? {
                val dates = entry.at("release_dates").arr().orEmpty()
                // Prefer theatrical (3) / digital (4) / physical (5), else any non-blank cert.
                for (type in listOf(3, 4, 5)) {
                    dates.firstOrNull { it.at("type").int() == type }
                        ?.at("certification").str()?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return dates.firstNotNullOfOrNull { it.at("certification").str()?.takeIf { c -> c.isNotBlank() } }
            }
            for (cc in CERT_COUNTRIES) {
                list.firstOrNull { it.at("iso_3166_1").str().equals(cc, true) }
                    ?.let(::fromCountry)?.let { return it }
            }
            return list.firstNotNullOfOrNull(::fromCountry)
        }

        /** TMDB's recommendation results as [Rec]s (media type from [tv] unless the result says otherwise). */
        internal fun parseRecs(d: JsonElement, tv: Boolean): List<Rec> =
            d.at("results").arr().orEmpty().take(20).mapNotNull { r ->
                parseRecItem(r, defaultTv = tv)
            }

        private fun parseRecItem(r: JsonElement, defaultTv: Boolean): Rec? {
            val id = r.at("id").str()?.takeIf { it.isNotEmpty() } ?: return null
            val isTv = when (r.at("media_type").str()) {
                "tv" -> true
                "movie" -> false
                else -> defaultTv
            }
            val title = (if (isTv) r.at("name").str() else r.at("title").str()) ?: r.at("title").str() ?: r.at("name").str()
                ?: return null
            val date = r.at("release_date").str() ?: r.at("first_air_date").str()
            val genres = r.at("genre_ids").arr()?.mapNotNull { it.int() }.orEmpty()
            return Rec(
                id = id,
                tv = isTv,
                title = title,
                year = date?.take(4)?.takeIf { it.length == 4 },
                rating = r.at("vote_average").dbl()?.takeIf { it > 0 },
                adult = r.at("adult").flag(),
                genreIds = genres,
            )
        }

        /** Parses `/3/person/{id}?append_to_response=combined_credits`, sorting top roles first. */
        internal fun parsePersonCredits(id: String, d: JsonElement): PersonCredits {
            val combined = d.at("combined_credits")
            val knownFor = d.at("known_for_department").str()?.takeIf { it.isNotBlank() }
            val cast = combined.at("cast").arr().orEmpty()
            val rawList = if (cast.isNotEmpty()) cast else combined.at("crew").arr().orEmpty()
            val talkChars = setOf("self", "herself", "himself", "guest", "")
            val seen = HashSet<String>()
            val ranked = rawList.mapNotNull { item ->
                val rec = parseRecItem(item, defaultTv = false) ?: return@mapNotNull null
                val key = "${if (rec.tv) "s" else "m"}:${rec.id}"
                if (!seen.add(key)) return@mapNotNull null
                val ch = item.at("character").str()?.trim()?.lowercase().orEmpty()
                val isTalkGuest = (10767 in rec.genreIds || 10763 in rec.genreIds) && (ch in talkChars)
                val pop = (item.at("popularity").dbl() ?: 0.0) + (item.at("vote_count").dbl() ?: 0.0) / 100.0
                val rank = if (isTalkGuest) pop - 1000.0 else pop
                rec to rank
            }
                .sortedByDescending { it.second }
                .take(80)
                .map { it.first }
            return PersonCredits(
                id = id,
                name = d.at("name").str().orEmpty(),
                photo = d.at("profile_path").str()?.let { "https://image.tmdb.org/t/p/w185$it" },
                bio = d.at("biography").str()?.takeIf { it.isNotBlank() },
                knownFor = knownFor,
                credits = ranked,
            )
        }

        /** The best logo: the title's own language, then English, then one without text; most votes first. */
        internal fun pickLogo(logos: List<JsonElement>, language: String?): String? {
            fun rank(l: JsonElement): Int = when (l.at("iso_639_1").str()) {
                null -> 2
                language -> 0
                "en" -> 1
                else -> 3
            }
            return logos
                .filter { it.at("file_path").str()?.endsWith(".svg") == false }
                .sortedWith(compareBy<JsonElement> { rank(it) }.thenByDescending { it.at("vote_average").dbl() ?: 0.0 })
                .firstOrNull()?.at("file_path").str()
        }

        fun cleanTitle(raw: String): String = TitleParts.parse(raw).title
            .replace(PREFIX, "")
            .replace(YEAR, "")
            .replace(TAGS, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
