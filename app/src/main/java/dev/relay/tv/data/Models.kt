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
    val plot: String?,
    val year: String?,
    val genre: String?,
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
    val start: Long, // epoch seconds
    val end: Long,
    val hasArchive: Boolean,
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
)

data class PlayRequest(
    val profileId: String,
    val items: List<PlayItem>,
    val index: Int,
    val live: Boolean = false,
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
