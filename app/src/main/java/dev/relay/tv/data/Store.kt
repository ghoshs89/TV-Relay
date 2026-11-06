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

    fun deleteProfile(pid: String) {
        profiles = profiles.filterNot { it.id == pid }
        write<List<FavItem>?>("fav_$pid", null)
        write<List<WatchEntry>?>("hist_$pid", null)
    }
}
