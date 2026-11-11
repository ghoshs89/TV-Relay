package dev.relay.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small on-device store: account, profiles, settings, and per-profile favourites and history.
 * [version] bumps on every write so Compose screens that read it recompose.
 */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, Any>()
    /** Changes to settings, profiles, favourites and the like. */
    val version = mutableIntStateOf(0)

    /** Changes to the watch history, kept apart so saving playback progress doesn't redraw every screen. */
    val historyVersion = mutableIntStateOf(0)

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

    /**
     * Saving to disk happens in the background, newest value per key (see [WriteBehind]); the value
     * and the version counters change at once so screens see it immediately. [flush] makes sure
     * everything is on disk.
     */
    private inline fun <reified T> write(key: String, v: T, history: Boolean = false) {
        if (v == null) {
            cache.remove(key)
            behind.put(key, null)
            // Removals are rare (a deleted profile); settle them now so nothing can read the old value back.
            behind.flush()
        } else {
            cache[key] = v as Any
            val serializer = kotlinx.serialization.serializer<T>()
            behind.put(key) { Jsons.json.encodeToString(serializer, v) }
        }
        if (history) historyVersion.intValue++ else version.intValue++
    }

    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-store").apply { isDaemon = true } }
    private val behind = WriteBehind(
        post = { task -> saver.schedule(task, SAVE_DELAY_MS, TimeUnit.MILLISECONDS) },
        save = { batch ->
            val t = System.nanoTime()
            val edit = prefs.edit()
            for ((k, text) in batch) if (text == null) edit.remove(k) else edit.putString(k, text)
            edit.apply()
            val ms = (System.nanoTime() - t) / 1_000_000
            if (ms > 8) AppLog.d("Store", "Saved ${batch.size} item(s) in $ms ms: ${batch.keys.joinToString()}")
        },
    )

    /** Saves anything still waiting. Called when the app stops, and before a backup is read or restored. */
    fun flush() = behind.flush()

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

    fun setFavs(pid: String, list: List<FavItem>) = write("fav_$pid", list)

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
        write("hist_$pid", (listOf(e) + history(pid).filterNot { it.key == e.key }).take(400), history = true)
    }

    fun removeHistory(pid: String, key: String) {
        write("hist_$pid", HistoryEdits.without(history(pid), key), history = true)
    }

    /** Empties "Continue watching": every movie and episode in progress goes; finished titles and channels stay. */
    fun clearContinue(pid: String) {
        write("hist_$pid", HistoryEdits.withoutContinue(history(pid)), history = true)
    }

    /** Takes a channel out of "Recently watched". */
    fun removeChannelHistory(pid: String, streamId: String) {
        write("hist_$pid", HistoryEdits.withoutChannel(history(pid), streamId), history = true)
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

    // ---------- Kids viewing time ----------
    // Plain prefs, not write(): the player adds to this every half minute and nothing on screen
    // needs to redraw for it.

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Milliseconds watched today on a profile. */
    fun usedToday(pid: String): Long {
        val v = prefs.getString("use_$pid", null) ?: return 0L
        return if (v.substringBefore('|') == today()) v.substringAfter('|').toLongOrNull() ?: 0L else 0L
    }

    fun addUsage(pid: String, ms: Long) {
        if (ms <= 0) return
        prefs.edit().putString("use_$pid", "${today()}|${usedToday(pid) + ms}").apply()
    }

    fun allowUntil(pid: String): Long = prefs.getLong("allow_$pid", 0L)

    /** A parent's extra time: [minutes] from now, lifting the daily limit and bedtime. */
    fun allowExtra(pid: String, minutes: Int) {
        prefs.edit().putLong("allow_$pid", System.currentTimeMillis() + minutes * 60_000L).apply()
        version.intValue++
    }

    fun viewingVerdict(pid: String): KidsLimits.Verdict {
        val p = profiles.firstOrNull { it.id == pid } ?: return KidsLimits.Verdict.Allowed(null)
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return KidsLimits.check(p.kids, p.dailyLimitMin, p.bedtime, p.wakeTime, usedToday(pid), nowMin, System.currentTimeMillis(), allowUntil(pid))
    }

    // ---------- Channel customisation ----------

    var channelCustom: ChannelCustom
        get() = read("chcustom", ChannelCustom())
        set(v) = write("chcustom", v)

    // ---------- Backup ----------

    /** Everything a backup holds (format 2), without the login, PINs or keys. */
    fun exportJson(appVersion: String): String {
        flush()
        val ps = profiles
        val b = BackupFile(
            appVersion = appVersion,
            created = System.currentTimeMillis(),
            profiles = ps,
            lockedProfiles = ps.filter { !it.pin.isNullOrEmpty() }.map { it.id },
            favourites = ps.associate { it.id to favs(it.id) }.filterValues { it.isNotEmpty() },
            history = ps.associate { it.id to history(it.id) }.filterValues { it.isNotEmpty() },
            settings = settings,
            channels = channelCustom,
            pinnedCategories = pinnedCats,
            liveSort = read<Map<String, Int>>("livesort", emptyMap()),
            seriesTracks = prefs.all.keys.filter { it.startsWith("trk_") }
                .mapNotNull { k -> k.removePrefix("trk_").toIntOrNull()?.let { id -> trackPref(id)?.let { id to it } } }
                .toMap(),
            reminders = reminders,
        )
        return Backups.encode(b)
    }

    /**
     * Restores a backup, replacing profiles, favourites, history, channel groups, sort choices,
     * series tracks, reminders and settings. The file is fully read and checked first; if it's
     * not valid nothing changes. PINs stay for profiles that already exist on this device; the
     * login, the parent PIN and API keys on this device are kept.
     */
    fun importJson(text: String): RestoreResult {
        flush()
        val b = Backups.decode(text)
        val oldPins = profiles.associate { it.id to it.pin }
        val newProfiles = b.profiles.map { it.copy(pin = oldPins[it.id]) }
        val ids = newProfiles.map { it.id }.toSet()
        val local = settings
        val newSettings = b.settings.copy(tmdbKey = local.tmdbKey, ratingsKey = local.ratingsKey, logUntil = local.logUntil)

        val edit = prefs.edit()
        prefs.all.keys
            .filter { k ->
                k in REPLACED || REPLACED_PREFIXES.any { p -> k.startsWith(p) }
            }
            .forEach { edit.remove(it) }
        fun put(key: String, json: String) = edit.putString(key, json)
        val j = Jsons.json
        put("profiles", j.encodeToString(newProfiles))
        put("settings", j.encodeToString(newSettings))
        put("chcustom", j.encodeToString(b.channels))
        put("pincats", j.encodeToString(b.pinnedCategories))
        put("livesort", j.encodeToString(b.liveSort))
        put("reminders", j.encodeToString(b.reminders))
        b.favourites.filterKeys { it in ids }.forEach { (pid, list) -> put("fav_$pid", j.encodeToString(list)) }
        b.history.filterKeys { it in ids }.forEach { (pid, list) -> put("hist_$pid", j.encodeToString(list)) }
        b.seriesTracks.forEach { (sid, t) -> put("trk_$sid", j.encodeToString(t)) }
        if (lastProfileId !in ids) edit.remove("last_profile")
        // Applied in one write so a restore can't stop halfway.
        if (!edit.commit()) throw java.io.IOException("Couldn't save the restored data")
        cache.clear()
        version.intValue++
        historyVersion.intValue++

        return RestoreResult(
            profiles = newProfiles.size,
            favourites = b.favourites.filterKeys { it in ids }.values.sumOf { it.size },
            history = b.history.filterKeys { it in ids }.values.sumOf { it.size },
            groups = b.channels.groups.size,
            needPin = newProfiles.filter { it.id in b.lockedProfiles && it.pin.isNullOrEmpty() }.map { it.name },
        )
    }

    companion object {
        const val PARENT_PIN = "parent_pin"

        /** Writes wait this long so quick repeats of one key are saved once. */
        private const val SAVE_DELAY_MS = 400L

        /** Keys a restore replaces as a whole; anything else (login, parent PIN) is left alone. */
        private val REPLACED = setOf("profiles", "settings", "chcustom", "pincats", "livesort", "reminders")
        private val REPLACED_PREFIXES = listOf("fav_", "hist_", "trk_", "lastcat_")
    }

    fun deleteProfile(pid: String) {
        profiles = profiles.filterNot { it.id == pid }
        write<List<FavItem>?>("fav_$pid", null)
        write<List<WatchEntry>?>("hist_$pid", null)
    }
}
