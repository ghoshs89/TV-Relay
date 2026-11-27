package dev.relay.tv.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Which settings travel between screens and which belong to one TV. Lip-sync, refresh-rate matching,
 * subtitle size, theme, keyboard, the pause buffer, the start screen and the sleep timer depend on the
 * TV and the room, so they stay put. A test fails if a setting is added to [Settings] and to neither
 * list, so a future setting cannot be forgotten.
 */
object SettingsSplit {

    val SHARED: Set<String> = linkedSetOf(
        "tmdbKey", "ratingsKey", "audioLang", "audioLangs", "subtitleLang", "autoNext", "hideAdult",
        "xmltvUrl", "epgOffsetMin", "posterScores", "langOrder", "langFeatured", "hiddenCats",
        "pinnedVod", "pinnedSeries", "mergeVariants", "preferredVersions", "fullGuideSearch",
        "watchNext", "skipBackSec", "skipForwardSec", "askStillWatching", "setupDone",
    )

    val PER_SCREEN: Set<String> = linkedSetOf(
        "liveExt", "userAgent", "logUntil", "matchFrameRate", "showStreamInfo", "audioDelayMs",
        "subSize", "subYellow", "subBox", "neutralTheme", "startOnLastChannel", "keyboard",
        "timeshift", "timeshiftMinutes", "startScreen", "sleepMinutes", "shareUsageStats", "includePrereleases",
    )

    /** Every field name of [Settings], read from its serializer. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun allFields(): Set<String> {
        val d = kotlinx.serialization.serializer<Settings>().descriptor
        return (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
    }

    fun toObject(s: Settings): JsonObject = Jsons.json.encodeToJsonElement(s) as JsonObject

    /** The shared fields whose value differs between [old] and [new]. */
    fun changed(old: Settings, new: Settings): Set<String> {
        if (old == new) return emptySet()
        val a = toObject(old)
        val b = toObject(new)
        return SHARED.filter { a[it] != b[it] }.toSet()
    }

    /** [stamps] with the time of every shared field that changed from [old] to [new] set to [now]. */
    fun stamp(old: Settings, new: Settings, stamps: Map<String, Long>, now: Long): Map<String, Long> {
        val c = changed(old, new)
        return if (c.isEmpty()) stamps else stamps + c.associateWith { now }
    }

    /**
     * When a shared field was last changed on this screen, for sync: its own stamp and nothing else.
     * A field never changed here has none, so an untouched default loses to the household's value
     * instead of being treated as a change made now.
     */
    fun changedAt(stamps: Map<String, Long>, field: String): Long? = stamps[field]

    /**
     * The stamps after a backup restore. A restore is a change made [now], so every shared field it
     * brings in is stamped; the [kept] fields (the API keys, which a restore leaves as they were) keep
     * the stamps they had.
     */
    fun restored(old: Map<String, Long>, now: Long, kept: Set<String>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (f in SHARED) {
            val t = if (f in kept) old[f] else now
            if (t != null) out[f] = t
        }
        return out
    }

    /** [local] with the given shared fields replaced; per-screen fields and unknown names are left alone. */
    fun withShared(local: Settings, values: Map<String, JsonElement>): Settings {
        val picked = values.filterKeys { it in SHARED }
        if (picked.isEmpty()) return local
        val merged = JsonObject(toObject(local) + picked)
        return try {
            Jsons.json.decodeFromJsonElement<Settings>(merged)
        } catch (e: Exception) {
            local
        }
    }
}
