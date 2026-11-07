package dev.relay.tv.data

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Rotten Tomatoes scores for one title, 1 to 100; null when Rotten Tomatoes has none. */
@Serializable
data class Score(
    val critics: Int? = null,
    val audience: Int? = null,
    val fetched: Long = 0L,
    /** Release year, so recent titles are refreshed sooner. */
    val year: Int? = null,
) {
    val none: Boolean get() = critics == null && audience == null
    val fresh: Boolean get() = (critics ?: 0) >= 60
}

/** Reads MDBList responses. Kept free of I/O so it can be tested against recorded replies. */
object MdbList {
    const val BASE = "https://api.mdblist.com"

    sealed interface Reply {
        data class Found(val critics: Int?, val audience: Int?) : Reply
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
        val ratings = root.at("ratings").arr() ?: return Reply.Found(null, null)
        var critics: Int? = null
        var audience: Int? = null
        for (r in ratings) {
            val v = r.at("value").dbl()?.toInt()?.takeIf { it in 1..100 }
            when (r.at("source").str()?.lowercase()) {
                "tomatoes" -> critics = critics ?: v
                "popcorn", "tomatoesaudience" -> audience = audience ?: v
            }
        }
        return Reply.Found(critics, audience)
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

/**
 * Saved scores and the daily request budget, in one small file. Recent releases are refreshed
 * after a day, older titles after a week, and "no score" answers after three days.
 */
class RatingsCache(private val file: File?, private val clock: () -> Long, private val maxEntries: Int = MAX_ENTRIES) {

    @Serializable
    private data class Data(
        val entries: Map<String, Score> = emptyMap(),
        val day: Long = 0L,
        val used: Int = 0,
        val pausedDay: Long = -1L,
    )

    private var data = Data()
    private val lock = Any()

    init {
        try {
            if (file != null && file.exists()) data = Jsons.json.decodeFromString(Data.serializer(), file.readText())
        } catch (_: Exception) {
            data = Data()
        }
    }

    fun get(key: String): Score? = synchronized(lock) { data.entries[key] }

    fun isFresh(s: Score): Boolean {
        val age = clock() - s.fetched
        val maxAge = when {
            s.none -> 3 * DAY
            s.year != null && s.year >= currentYear() -> DAY
            else -> 7 * DAY
        }
        return age in 0 until maxAge
    }

    fun put(key: String, s: Score) {
        synchronized(lock) {
            var m = data.entries + (key to s)
            if (m.size > maxEntries) {
                // Oldest lookups go first.
                m = m.entries.sortedByDescending { it.value.fetched }.take(maxEntries).associate { it.key to it.value }
            }
            data = data.copy(entries = m)
        }
        save()
    }

    /** True when another request may be made today; counts it. */
    fun spend(): Boolean {
        val ok = synchronized(lock) {
            val today = clock() / DAY
            if (data.day != today) data = data.copy(day = today, used = 0)
            if (data.pausedDay == today || data.used >= DAILY_LIMIT) {
                false
            } else {
                data = data.copy(used = data.used + 1)
                true
            }
        }
        save()
        return ok
    }

    /** The service said the limit is reached: no more requests until tomorrow. */
    fun pauseToday() {
        synchronized(lock) { data = data.copy(pausedDay = clock() / DAY) }
        save()
    }

    val usedToday: Int get() = synchronized(lock) { if (data.day == clock() / DAY) data.used else 0 }

    private fun save() {
        val f = file ?: return
        val text = synchronized(lock) { Jsons.json.encodeToString(Data.serializer(), data) }
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    private fun currentYear() = Calendar.getInstance().apply { timeInMillis = clock() }.get(Calendar.YEAR)

    companion object {
        const val DAY = 86_400_000L
        const val DAILY_LIMIT = 900
        const val MAX_ENTRIES = 5000
    }
}

/**
 * Rotten Tomatoes critics and audience scores through MDBList (free key, 1,000 requests a day).
 * Lookups happen only on detail pages; posters show scores already saved.
 */
class Ratings(client: OkHttpClient, cacheFile: File?, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    val cache = RatingsCache(cacheFile, clock)

    /** Bumps when a score is saved, so posters showing saved scores update. */
    val version = mutableIntStateOf(0)

    var base = MdbList.BASE

    /** Saved score for a provider title ("m:123" or "s:45"), fresh or not. */
    fun saved(key: String): Score? {
        version.intValue
        return cache.get(key)
    }

    /**
     * Score for a provider title. [tmdbId] comes from the provider or TMDB; without one, MDBList's
     * search is tried with the title and year. Returns the saved score when lookups are paused.
     */
    suspend fun lookup(apiKey: String, key: String, tv: Boolean, tmdbId: String?, title: String, year: String?): Score? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext null
            val saved = cache.get(key)
            if (saved != null && cache.isFresh(saved)) return@withContext saved
            val type = if (tv) "show" else "movie"
            val y = year?.take(4)?.toIntOrNull()
            try {
                val id = tmdbId?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: run {
                    if (!cache.spend()) return@withContext saved
                    val url = "$base/search/$type".toHttpUrl().newBuilder()
                        .addQueryParameter("query", Tmdb.cleanTitle(title))
                        .apply { if (y != null) addQueryParameter("year", y.toString()) }
                        .addQueryParameter("apikey", apiKey)
                        .build()
                    val (code, root) = get(url.toString())
                    if (code == 429) {
                        cache.pauseToday()
                        return@withContext saved
                    }
                    MdbList.pickSearch(root, Tmdb.cleanTitle(title), y)
                }
                if (id == null) {
                    AppLog.d("Ratings", "No confident match for $key")
                    return@withContext keep(key, Score(fetched = clock(), year = y))
                }
                if (!cache.spend()) {
                    AppLog.i("Ratings", "Daily request budget used; showing saved scores until tomorrow")
                    return@withContext saved
                }
                val (code, root) = get("$base/tmdb/$type/$id?apikey=$apiKey")
                when (val r = MdbList.parseItem(code, root)) {
                    is MdbList.Reply.Found -> {
                        AppLog.d("Ratings", "$key (tmdb $type $id): critics ${r.critics ?: "-"}, audience ${r.audience ?: "-"}")
                        keep(key, Score(r.critics, r.audience, clock(), y))
                    }
                    MdbList.Reply.NotFound -> keep(key, Score(fetched = clock(), year = y))
                    MdbList.Reply.LimitReached -> {
                        AppLog.w("Ratings", "MDBList limit reached; pausing until tomorrow")
                        cache.pauseToday()
                        saved
                    }
                    is MdbList.Reply.Failed -> {
                        AppLog.w("Ratings", "Lookup failed for $key: ${r.reason}")
                        saved
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Ratings", "Lookup failed for $key", e)
                saved
            }
        }

    /** Checks a key with one known title. */
    suspend fun validate(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val (code, root) = get("$base/tmdb/movie/550?apikey=$apiKey")
            MdbList.parseItem(code, root) is MdbList.Reply.Found
        } catch (e: Exception) {
            false
        }
    }

    private fun keep(key: String, s: Score): Score {
        cache.put(key, s)
        version.intValue++
        return s
    }

    private fun get(url: String): Pair<Int, JsonElement?> =
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            r.code to (try {
                Jsons.json.parseToJsonElement(body)
            } catch (_: Exception) {
                null
            })
        }
}
