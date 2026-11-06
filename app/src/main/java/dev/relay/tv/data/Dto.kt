@file:Suppress("PropertyName")

package dev.relay.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Every field is JsonElement so a provider sending "5", 5, "", null or [] never breaks a whole list.

@Serializable
internal class CategoryDto(
    val category_id: JsonElement? = null,
    val category_name: JsonElement? = null,
)

@Serializable
internal class LiveDto(
    val num: JsonElement? = null,
    val name: JsonElement? = null,
    val stream_id: JsonElement? = null,
    val stream_icon: JsonElement? = null,
    val epg_channel_id: JsonElement? = null,
    val category_id: JsonElement? = null,
    val tv_archive: JsonElement? = null,
    val tv_archive_duration: JsonElement? = null,
)

@Serializable
internal class VodDto(
    val name: JsonElement? = null,
    val stream_id: JsonElement? = null,
    val stream_icon: JsonElement? = null,
    val rating: JsonElement? = null,
    val category_id: JsonElement? = null,
    val container_extension: JsonElement? = null,
    val added: JsonElement? = null,
    val tmdb: JsonElement? = null,
    val year: JsonElement? = null,
)

@Serializable
internal class SeriesDto(
    val name: JsonElement? = null,
    val series_id: JsonElement? = null,
    val cover: JsonElement? = null,
    val plot: JsonElement? = null,
    val rating: JsonElement? = null,
    val category_id: JsonElement? = null,
    val releaseDate: JsonElement? = null,
    val release_date: JsonElement? = null,
    val genre: JsonElement? = null,
    val last_modified: JsonElement? = null,
    val tmdb: JsonElement? = null,
    val year: JsonElement? = null,
)
