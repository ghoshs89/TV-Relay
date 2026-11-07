package dev.relay.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * Small on-device store: account, profiles, settings, and per-profile favourites and history.
 * [version] bumps on every write so Compose screens that read it recompose.
 */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, Any>()
    val version = mutableIntStateOf(0)

    private inline fun <reified T> read(key: String, def: T): T {
        (cache[key] as? T)?.let { return it }
        val raw = prefs.getString(key, null) ?: return def
        val v: T = try {
            Jsons.json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            return def
        }
        if (v != null) cache[key] = v as Any
        return v
    }

    private inline fun <reified T> write(key: String, v: T) {
        if (v == null) {
            cache.remove(key)
            prefs.edit().remove(key).apply()
        } else {
            cache[key] = v as Any
            prefs.edit().putString(key, Jsons.json.encodeToString(v)).apply()
        }
        version.intValue++
    }

    var account: Account?
        get() = read<Account?>("account", null)
        set(v) = write("account", v)

    var profiles: List<Profile>
        get() = read<List<Profile>>("profiles", emptyList())
        set(v) = write("profiles", v)

    var settings: Settings
        get() = read("settings", Settings())
        set(v) = write("settings", v)

    var activeHost: String?
        get() = prefs.getString("active_host", null)
        set(v) { prefs.edit().putString("active_host", v).apply() }

    var lastProfileId: String?
        get() = prefs.getString("last_profile", null)
        set(v) { prefs.edit().putString("last_profile", v).apply() }

    /** Salted hash of the parent PIN (see [ParentPin]); null when none is set. Never backed up. */
    var parentPin: String?
        get() = prefs.getString(PARENT_PIN, null)
        set(v) {
            if (v == null) prefs.edit().remove(PARENT_PIN).apply() else prefs.edit().putString(PARENT_PIN, v).apply()
            version.intValue++
        }

    val hasParentPin: Boolean get() = !parentPin.isNullOrEmpty()

    // ---------- Favourites ----------

    fun favs(pid: String): List<FavItem> = read<List<FavItem>>("fav_$pid", emptyList())

    fun isFav(pid: String, kind: String, id: Int) = favs(pid).any { it.kind == kind && it.id == id }

    fun toggleFav(pid: String, item: FavItem) {
        val list = favs(pid)
        val exists = list.any { it.kind == item.kind && it.id == item.id }
        write("fav_$pid", if (exists) list.filterNot { it.kind == item.kind && it.id == item.id } else listOf(item) + list)
    }

    // ---------- History / resume ----------

    fun history(pid: String): List<WatchEntry> = read<List<WatchEntry>>("hist_$pid", emptyList())

    fun entry(pid: String, key: String): WatchEntry? = history(pid).firstOrNull { it.key == key }

    fun record(pid: String, e: WatchEntry) {
        write("hist_$pid", (listOf(e) + history(pid).filterNot { it.key == e.key }).take(400))
    }

    fun removeHistory(pid: String, key: String) {
        write("hist_$pid", history(pid).filterNot { it.key == key })
    }

    // ---------- Live TV browsing ----------

    /** Sort choice per category (index into LiveBrowse.SORTS). */
    fun liveSort(cat: String): Int = read<Map<String, Int>>("livesort", emptyMap())[cat] ?: 0

    fun setLiveSort(cat: String, sort: Int) {
        write("livesort", read<Map<String, Int>>("livesort", emptyMap()) + (cat to sort))
    }

    var pinnedCats: List<String>
        get() = read<List<String>>("pincats", emptyList())
        set(v) = write("pincats", v)

    /** Plain prefs: saving the last category must not trigger recomposition. */
    fun lastLiveCat(pid: String): String? = prefs.getString("lastcat_$pid", null)

    fun setLastLiveCat(pid: String, cat: String) {
        prefs.edit().putString("lastcat_$pid", cat).apply()
    }

    // ---------- Reminders ----------

    var reminders: List<Reminder>
        get() = read<List<Reminder>>("reminders", emptyList())
        set(v) = write("reminders", v)

    // ---------- Per-series tracks ----------

    fun trackPref(seriesId: Int): TrackPref? = read<TrackPref?>("trk_$seriesId", null)

    fun setTrackPref(seriesId: Int, p: TrackPref) = write("trk_$seriesId", p)

    // ---------- Channel customisation ----------

    var channelCustom: ChannelCustom
        get() = read("chcustom", ChannelCustom())
        set(v) = write("chcustom", v)

    // ---------- Backup ----------

    /** Everything except the login, as one JSON document. */
    fun exportJson(): String {
        val map = prefs.all
            .filterKeys { it != "account" && it != "active_host" && it != PARENT_PIN }
            .mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v?.toString()) }
        val root = kotlinx.serialization.json.buildJsonObject {
            put("app", kotlinx.serialization.json.JsonPrimitive("relay"))
            put("version", kotlinx.serialization.json.JsonPrimitive(1))
            put("data", kotlinx.serialization.json.JsonObject(map))
        }
        return root.toString()
    }

    /** Restores a backup made by [exportJson]. Returns the number of entries restored. */
    fun importJson(text: String): Int {
        val root = Jsons.json.parseToJsonElement(text)
        if (root.at("app").str() != "relay") throw IllegalArgumentException("Not a Relay backup file")
        val data = root.at("data") as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("Backup has no data")
        val edit = prefs.edit()
        var n = 0
        for ((k, v) in data) {
            if (k == "account" || k == "active_host" || k == PARENT_PIN) continue
            val value = v.str() ?: continue
            edit.putString(k, value)
            n++
        }
        edit.apply()
        cache.clear()
        version.intValue++
        return n
    }

    companion object {
        const val PARENT_PIN = "parent_pin"
    }

    fun deleteProfile(pid: String) {
        profiles = profiles.filterNot { it.id == pid }
        write<List<FavItem>?>("fav_$pid", null)
        write<List<WatchEntry>?>("hist_$pid", null)
    }
}
