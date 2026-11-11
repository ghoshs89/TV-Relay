package dev.relay.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object Jsons {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }
}

// ---------- Persisted ----------

@Serializable
data class Account(
    val name: String,
    val hosts: List<String>,
    val username: String,
    val password: String,
)

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val pin: String? = null,
    val kids: Boolean = false,
    val color: Int = 0,
    // Batch 5: per-profile language order (null = household default) and Home layout.
    val langOrder: List<String>? = null,
    val langFeatured: List<String>? = null,
    val homeRows: List<String>? = null,
    // Batch 7: kids viewing limits. 0 / "" = no limit.
    val dailyLimitMin: Int = 0,
    val bedtime: String = "",
    val wakeTime: String = "",
)

@Serializable
data class FavItem(
    val kind: String, // live | movie | series
    val id: Int,
    val name: String,
    val image: String? = null,
)

@Serializable
data class WatchEntry(
    val key: String,
    val kind: String, // live | movie | episode
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val streamId: String,
    val ext: String,
    val seriesId: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val updated: Long = 0,
) {
    val inProgress: Boolean
        get() = duration > 0 && position > 30_000 && position < duration * 0.95
    val finished: Boolean
        get() = duration > 0 && position >= duration * 0.95
}

@Serializable
data class Settings(
    val tmdbKey: String = "",
    val audioLang: String = "en",
    val subtitleLang: String = "",
    val autoNext: Boolean = true,
    val liveExt: String = "ts",
    val hideAdult: Boolean = false,
    val userAgent: String = "RelayTV/1.0",
    val logUntil: Long = 0L,
    // Batch 1
    val matchFrameRate: Boolean = false,
    val showStreamInfo: Boolean = true,
    val audioDelayMs: Int = 0,
    val subSize: Int = 1, // 0 small, 1 medium, 2 large, 3 extra large
    val subYellow: Boolean = false,
    val subBox: Boolean = true,
    val neutralTheme: Boolean = false,
    val startOnLastChannel: Boolean = false,
    val xmltvUrl: String = "",
    val epgOffsetMin: Int = 0,
    // Batch 2
    val keyboard: String = "auto", // auto | relay | system
    val timeshift: Boolean = true,
    val timeshiftMinutes: Int = 30,
    // Batch 3
    val ratingsKey: String = "", // MDBList, for Rotten Tomatoes scores
    val posterScores: Boolean = true,
    // Batch 5: household language defaults, library setup, hidden categories ("live:12", "vod:7",
    // "series:3", or "livegroup:IN" for a whole country), ordered audio and subtitle languages.
    val langOrder: List<String> = listOf("en", "hi", "bn", "south", "bd"),
    val langFeatured: List<String> = listOf("en", "hi", "bn"),
    val setupDone: Boolean = false,
    val hiddenCats: Set<String> = emptySet(),
    val audioLangs: String = "", // "" = follow my languages
    val pinnedVod: List<String> = emptyList(),
    val pinnedSeries: List<String> = emptyList(),
    // Batch 6
    val mergeVariants: Boolean = true,
    /** Chosen version per channel, by version key. */
    val preferredVersions: Map<String, Int> = emptyMap(),
    val startScreen: String = "home", // home | live | guide
    val fullGuideSearch: Boolean = false,
    // Batch 7
    /** Movies and episodes in progress on the Google TV home screen. */
    val watchNext: Boolean = true,
)

/** A programme the viewer wants to be told about when it starts. Times are as shown in the guide. */
@Serializable
data class Reminder(
    val channelId: Int,
    val channelName: String,
    val title: String,
    val start: Long,
    val end: Long,
)

/** Audio and subtitle choice remembered per series. */
@Serializable
data class TrackPref(
    val audio: String? = null,
    val text: String? = null,
    val textOff: Boolean = false,
)

@Serializable
data class ChannelGroup(
    val id: String,
    val name: String,
    val channelIds: List<Int> = emptyList(),
)

/** Device-wide channel customisation: renames and the user's own ordered groups. */
@Serializable
data class ChannelCustom(
    val renames: Map<Int, String> = emptyMap(),
    val groups: List<ChannelGroup> = emptyList(),
)

// ---------- Domain ----------

data class Category(val id: String, val name: String, val adult: Boolean = false)

data class Channel(
    val id: Int,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val archiveDays: Int,
) {
    val hasArchive: Boolean get() = archiveDays > 0
}

data class Movie(
    val id: Int,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: Double,
    val ext: String,
    val added: Long,
    val tmdbId: String?,
    val year: String?,
)

data class Series(
    val id: Int,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val rating: Double,
    val year: String?,
    val added: Long,
    val tmdbId: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String?,
    val image: String?,
    val durationSecs: Int?,
)

data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val durationSecs: Int?,
    val backdrop: String?,
    val year: String?,
    val rating: Double?,
    val tmdbId: String?,
    val ext: String?,
)

data class SeriesInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val backdrop: String?,
    val rating: Double?,
    val year: String?,
    val tmdbId: String?,
    val seasons: Map<Int, List<Episode>>,
)

data class EpgEntry(
    val title: String,
    val description: String,
    val start: Long, // epoch seconds, as displayed (after any guide offset)
    val end: Long,
    val hasArchive: Boolean,
    // Provider's original times; catch-up URLs must use these, not the displayed ones.
    val rawStart: Long = start,
    val rawEnd: Long = end,
)

data class AccountInfo(
    val status: String?,
    val expires: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
)

data class PlayItem(
    val key: String,
    val kind: String, // live | movie | episode | catchup
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val streamId: String,
    val ext: String,
    val seriesId: Int? = null,
    val resumeMs: Long = 0,
    val urls: List<String> = emptyList(),
    /** Live: stream ids of other versions of this channel, tried in order if this one fails. */
    val alternates: List<String> = emptyList(),
    /**
     * False when [alternates] hasn't been worked out yet (long channel lists build entries lazily);
     * the player then asks [PlayRequest.fallbacks] the first time the channel fails.
     */
    val alternatesKnown: Boolean = true,
)

data class PlayRequest(
    val profileId: String,
    val items: List<PlayItem>,
    val index: Int,
    val live: Boolean = false,
    /** True when the caller already removed what this profile may not play; [Restrictions.admit] then only checks the chosen item. */
    val vetted: Boolean = false,
    /** Live: other versions (HD, FHD, backup) of a channel by stream id, best first; null when versions aren't merged. */
    val fallbacks: ((Int) -> List<String>)? = null,
)

// ---------- Tolerant JSON access (providers mix strings, numbers and nulls) ----------

fun JsonElement?.str(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val c = p.content.trim()
    return if (c.isEmpty() || c == "null") null else c
}

fun JsonElement?.int(): Int? = str()?.toDoubleOrNull()?.toInt()
fun JsonElement?.long(): Long? = str()?.toDoubleOrNull()?.toLong()
fun JsonElement?.dbl(): Double? = str()?.toDoubleOrNull()
fun JsonElement?.flag(): Boolean = str()?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
