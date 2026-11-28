package dev.relay.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small on-device store: account, profiles, settings, and per-profile favourites and history.
 * [version] bumps on every write so Compose screens that read it recompose.
 */
class Store(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, Any>()

    /**
     * When each stored item was last written in this run, so a change can be given the time it was made
     * when it is synced a little later. Not saved: after a restart, sync falls back to its own clock.
     */
    private val touched = ConcurrentHashMap<String, Long>()
    /** Changes to settings, profiles, favourites and the like. */
    val version = mutableIntStateOf(0)

    /** Changes to the watch history, kept apart so saving playback progress doesn't redraw every screen. */
    val historyVersion = mutableIntStateOf(0)

    private inline fun <reified T> read(key: String, def: T): T {
        val cached = cache[key]
        if (cached === CORRUPT) return def
        (cached as? T)?.let { return it }
        val raw = prefs.getString(key, null) ?: return def
        val v: T = try {
            Jsons.json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            AppLog.w("Store", "Corrupt stored value for $key; using default", e)
            cache.putIfAbsent(key, CORRUPT)
            return def
        }
        if (v != null) {
            val existing = cache.putIfAbsent(key, v as Any)
            if (existing != null && existing !== CORRUPT) (existing as? T)?.let { return it }
        }
        return v
    }

    /**
     * Saving to disk happens in the background, newest value per key (see [WriteBehind]); the value
     * and the version counters change at once so screens see it immediately. [flush] makes sure
     * everything is on disk.
     */
    private inline fun <reified T> write(key: String, v: T, history: Boolean = false, sync: Boolean = !history) {
        touched[key] = System.currentTimeMillis()
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
        if (sync && !quiet) onSyncChange?.invoke()
    }

    /** Set by the app: told when something that syncs was changed here (it waits a little, then syncs). */
    @Volatile var onSyncChange: (() -> Unit)? = null

    /** True while a sync is writing its result, so that is not mistaken for a change made here. */
    @Volatile private var quiet = false

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
        set(v) {
            // Remember when each setting that syncs was changed, so the latest change wins between screens.
            val old = read("settings", Settings())
            val stamps = read<Map<String, Long>>("settings_at", emptyMap())
            val updated = SettingsSplit.stamp(old, v, stamps, System.currentTimeMillis())
            val shared = updated !== stamps
            if (shared) write("settings_at", updated, sync = false)
            write("settings", v, sync = shared)
        }

    /** A random name for this screen in the sync file; made once and kept. */
    val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            .also { prefs.edit().putString("device_id", it).apply() }
    }

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
            touched[PARENT_PIN] = System.currentTimeMillis()
            if (v == null) prefs.edit().remove(PARENT_PIN).apply() else prefs.edit().putString(PARENT_PIN, v).apply()
            version.intValue++
            if (!quiet) onSyncChange?.invoke()
        }

    val hasParentPin: Boolean get() = !parentPin.isNullOrEmpty()

    // ---------- Favourites ----------

    fun favs(pid: String): List<FavItem> = read<List<FavItem>>("fav_$pid", emptyList())

    fun setFavs(pid: String, list: List<FavItem>) = write("fav_$pid", list)

    fun isFav(pid: String, kind: String, id: Int) = favs(pid).any { it.kind == kind && it.id == id }

    fun toggleFav(pid: String, item: FavItem) {
        val list = favs(pid)
        val exists = list.any { it.kind == item.kind && it.id == item.id }
        write(
            "fav_$pid",
            if (exists) list.filterNot { it.kind == item.kind && it.id == item.id } else listOf(item.copy(at = System.currentTimeMillis())) + list,
        )
    }

    /**
     * Once per screen: profiles with their own Home layout get the Watch next rows (see
     * [HomeLayout.withWatchNext]). Profiles on the default layout get them with the new defaults.
     */
    fun addWatchNextRows() {
        if (prefs.getBoolean(WATCH_NEXT_ROWS, false)) return
        val ps = profiles
        val next = ps.map { p -> p.homeRows?.let { p.copy(homeRows = HomeLayout.withWatchNext(it)) } ?: p }
        if (next != ps) profiles = next
        prefs.edit().putBoolean(WATCH_NEXT_ROWS, true).apply()
    }

    // ---------- Dismissals ("Not interested", Up next) ----------

    /** What this profile dismissed, item key to when (see [Dismissals]). */
    fun dismissed(pid: String): Map<String, Long> = read<Map<String, Long>>("dis_$pid", emptyMap())

    fun dismiss(pid: String, item: String) {
        write("dis_$pid", Dismissals.add(dismissed(pid), item, System.currentTimeMillis()))
    }

    // ---------- History / resume ----------

    fun history(pid: String): List<WatchEntry> = read<List<WatchEntry>>("hist_$pid", emptyList())

    fun entry(pid: String, key: String): WatchEntry? = history(pid).firstOrNull { it.key == key }

    fun record(pid: String, e: WatchEntry) {
        write("hist_$pid", (listOf(e) + history(pid).filterNot { it.key == e.key }).take(400), history = true)
    }

    fun removeHistory(pid: String, key: String) {
        write("hist_$pid", HistoryEdits.without(history(pid), key), history = true, sync = true)
    }

    /** Empties "Continue watching": every movie and episode in progress goes; finished titles and channels stay. */
    fun clearContinue(pid: String) {
        write("hist_$pid", HistoryEdits.withoutContinue(history(pid)), history = true, sync = true)
    }

    /** Takes a channel out of "Recently watched". */
    fun removeChannelHistory(pid: String, streamId: String) {
        write("hist_$pid", HistoryEdits.withoutChannel(history(pid), streamId), history = true, sync = true)
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

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /**
     * Milliseconds watched today on a profile, on this screen and (once sync has told us) on the others,
     * so a daily limit means the same time in total wherever the child watches.
     */
    fun usedToday(pid: String): Long =
        DailyUsage.total(prefs.getString("use_$pid", null), read<Map<String, String>>("usx_$pid", emptyMap()), today())

    fun addUsage(pid: String, ms: Long) {
        if (ms <= 0) return
        val day = today()
        prefs.edit().putString("use_$pid", DailyUsage.format(day, DailyUsage.ms(prefs.getString("use_$pid", null), day) + ms)).apply()
    }

    fun allowUntil(pid: String): Long = prefs.getLong("allow_$pid", 0L)

    /** A parent's extra time: [minutes] from now, lifting the daily limit and bedtime. */
    fun allowExtra(pid: String, minutes: Int) {
        touched["allow_$pid"] = System.currentTimeMillis()
        prefs.edit().putLong("allow_$pid", System.currentTimeMillis() + minutes * 60_000L).apply()
        version.intValue++
        if (!quiet) onSyncChange?.invoke()
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
            dismissed = ps.associate { it.id to dismissed(it.id) }.filterValues { it.isNotEmpty() },
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
        // A restore is a change made now: every shared setting it brings in is stamped so it spreads,
        // while the API keys (kept from this screen) keep their own stamps.
        val t = System.currentTimeMillis()
        val newStamps = SettingsSplit.restored(read<Map<String, Long>>("settings_at", emptyMap()), t, RESTORE_KEEPS)

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
        put("settings_at", j.encodeToString(newStamps))
        put("chcustom", j.encodeToString(b.channels))
        put("pincats", j.encodeToString(b.pinnedCategories))
        put("livesort", j.encodeToString(b.liveSort))
        put("reminders", j.encodeToString(b.reminders))
        b.favourites.filterKeys { it in ids }.forEach { (pid, list) -> put("fav_$pid", j.encodeToString(list)) }
        b.history.filterKeys { it in ids }.forEach { (pid, list) -> put("hist_$pid", j.encodeToString(list)) }
        b.seriesTracks.forEach { (sid, t) -> put("trk_$sid", j.encodeToString(t)) }
        b.dismissed.filterKeys { it in ids }.forEach { (pid, m) -> put("dis_$pid", j.encodeToString(m)) }
        if (lastProfileId !in ids) edit.remove("last_profile")
        // Applied in one write so a restore can't stop halfway.
        if (!edit.commit()) throw java.io.IOException("Couldn't save the restored data")
        cache.clear()
        // A restore is a change made now, so with sync on it spreads to the other screens.
        listOf("profiles", "settings", "chcustom", "pincats", "livesort", "reminders").forEach { touched[it] = t }
        ids.forEach { touched["fav_$it"] = t; touched["hist_$it"] = t; touched["dis_$it"] = t }
        b.seriesTracks.keys.forEach { touched["trk_$it"] = t }
        version.intValue++
        historyVersion.intValue++
        onSyncChange?.invoke()

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
        private const val WATCH_NEXT_ROWS = "home_rows_watch_next"
        private val CORRUPT = Any()

        /** Writes wait this long so quick repeats of one key are saved once. */
        private const val SAVE_DELAY_MS = 400L

        /** Keys a restore replaces as a whole; anything else (login, parent PIN) is left alone. */
        private val REPLACED = setOf("profiles", "settings", "settings_at", "chcustom", "pincats", "livesort", "reminders")
        private val REPLACED_PREFIXES = listOf("fav_", "hist_", "trk_", "lastcat_", "dis_")

        /** Shared settings a restore leaves as this screen had them. */
        private val RESTORE_KEEPS = setOf("tmdbKey", "ratingsKey")

        /**
         * Applies incoming shared settings field-by-field onto [current], leaving per-screen settings and
         * any shared field changed locally since [snapshot] untouched.
         */
        internal fun mergeSharedSettings(snapshot: Settings, target: Settings, current: Settings): Settings {
            val snapObj = SettingsSplit.toObject(snapshot)
            val targetObj = SettingsSplit.toObject(target)
            val curObj = SettingsSplit.toObject(current)
            val accepted = LinkedHashMap<String, JsonElement>()
            for (f in SettingsSplit.SHARED) {
                val t = targetObj[f]
                if (t != null && t != snapObj[f] && curObj[f] == snapObj[f]) accepted[f] = t
            }
            return if (accepted.isEmpty()) current else SettingsSplit.withShared(current, accepted)
        }

        internal fun sumTodayUsage(intoRaw: String?, fromRaw: String?, today: String): String? {
            val sum = DailyUsage.ms(intoRaw, today) + DailyUsage.ms(fromRaw, today)
            return if (sum > 0L) DailyUsage.format(today, sum) else intoRaw?.takeIf { DailyUsage.ms(it, today) > 0L }
        }
    }

    // ---------- Sync ----------

    /** Everything that syncs, as plain values. The screens list is filled in by the caller. */
    fun syncSnapshot(): SyncData {
        val ps = profiles
        val trkKeys = (prefs.all.keys + cache.keys).filter { it.startsWith("trk_") }
        return SyncData(
            profiles = ps,
            favs = ps.associate { it.id to favs(it.id) }.filterValues { it.isNotEmpty() },
            history = ps.associate { it.id to history(it.id) }.filterValues { it.isNotEmpty() },
            settings = settings,
            settingsAt = read<Map<String, Long>>("settings_at", emptyMap()),
            tracks = trkKeys
                .mapNotNull { k -> k.removePrefix("trk_").toIntOrNull()?.let { id -> trackPref(id)?.let { id to it } } }
                .toMap(),
            reminders = reminders,
            liveSort = read<Map<String, Int>>("livesort", emptyMap()),
            pinnedCats = pinnedCats,
            channels = channelCustom,
            parentPin = parentPin?.takeIf { it.isNotEmpty() },
            account = account,
            useOwn = ps.mapNotNull { p -> prefs.getString("use_${p.id}", null)?.let { p.id to it } }.toMap(),
            useOthers = ps.associate { it.id to read<Map<String, String>>("usx_${it.id}", emptyMap()) }.filterValues { it.isNotEmpty() },
            allow = ps.mapNotNull { p -> prefs.getLong("allow_${p.id}", 0L).takeIf { it > 0 }?.let { p.id to it } }.toMap(),
            dismissed = ps.associate { it.id to dismissed(it.id) }.filterValues { it.isNotEmpty() },
        )
    }

    /**
     * Writes what sync decided. Each kind of data is written only if it is still what [snapshot] saw and
     * differs from [target], so something the viewer changed while the sync was running is never
     * overwritten; the next run picks it up as a normal change. Call on the main thread.
     */
    fun syncApply(snapshot: SyncData, target: SyncData): SyncApplied {
        quiet = true
        try {
            return applyUnquiet(snapshot, target)
        } finally {
            quiet = false
        }
    }

    /** Apply the remote change to each item that the viewer left untouched during this run. */
    private fun <T, K> mergeSyncList(before: List<T>, remote: List<T>, current: List<T>, key: (T) -> K): List<T> {
        if (before == current) return remote
        if (before == remote) return current
        val was = before.associateBy(key)
        val now = current.associateBy(key)
        val incoming = remote.associateBy(key)
        val common = was.keys.intersect(now.keys)
        val localOrderChanged = now.keys.any { it !in was } ||
            was.keys.filter { it in common } != now.keys.filter { it in common }
        val order = if (localOrderChanged) now.keys + incoming.keys else incoming.keys + now.keys
        return order.mapNotNull { id -> if (now[id] != was[id]) now[id] else incoming[id] }
    }

    private fun applyUnquiet(snapshot: SyncData, target: SyncData): SyncApplied {
        val cur = syncSnapshot()
        var changes = 0
        fun <T> go(was: T, new: T, now: T, apply: () -> Unit): Boolean {
            if (new == was || now != was) return false
            apply()
            changes++
            return true
        }

        val profilesChanged = go(snapshot.profiles, target.profiles, cur.profiles) { profiles = target.profiles }
        val appliedProfiles = if (profilesChanged) target.profiles else cur.profiles
        val ids = appliedProfiles.map { it.id }.toSet()
        val gone = cur.profiles.map { it.id }.toSet() - ids
        for (pid in gone) {
            removeProfileKeys(pid)
            changes++
        }
        val targetIds = target.profiles.map { it.id }.toSet()
        for (pid in ids.intersect(targetIds)) {
            val favs = cur.favs[pid].orEmpty()
            val mergedFavs = mergeSyncList(snapshot.favs[pid].orEmpty(), target.favs[pid].orEmpty(), favs) {
                it.kind to it.id
            }
            if (mergedFavs != favs) {
                write("fav_$pid", mergedFavs)
                changes++
            }
            val history = cur.history[pid].orEmpty()
            val mergedHistory = mergeSyncList(snapshot.history[pid].orEmpty(), target.history[pid].orEmpty(), history, WatchEntry::key)
                .sortedWith(compareByDescending<WatchEntry> { it.updated }.thenBy { it.key })
                .take(SyncMerge.HISTORY_CAP)
            if (mergedHistory != history) {
                write("hist_$pid", mergedHistory, history = true)
                changes++
            }
            go(snapshot.dismissed[pid].orEmpty(), target.dismissed[pid].orEmpty(), cur.dismissed[pid].orEmpty()) {
                write("dis_$pid", target.dismissed[pid].orEmpty())
            }
            go(snapshot.useOthers[pid].orEmpty(), target.useOthers[pid].orEmpty(), cur.useOthers[pid].orEmpty()) {
                write("usx_$pid", target.useOthers[pid].orEmpty())
            }
            go(snapshot.allow[pid], target.allow[pid], cur.allow[pid]) {
                val until = target.allow[pid]
                if (until == null) prefs.edit().remove("allow_$pid").apply()
                else prefs.edit().putLong("allow_$pid", until).apply()
                version.intValue++
            }
        }
        val mergedSettings = mergeSharedSettings(snapshot.settings, target.settings, cur.settings)
        val settingsChanged = if (mergedSettings != cur.settings) {
            // Not through the setter: a change that came from another screen is not a change made here.
            write("settings", mergedSettings)
            changes++
            true
        } else false
        for (sid in snapshot.tracks.keys + target.tracks.keys) {
            go(snapshot.tracks[sid], target.tracks[sid], cur.tracks[sid]) {
                val t = target.tracks[sid]
                if (t == null) write<TrackPref?>("trk_$sid", null) else write("trk_$sid", t)
            }
        }
        go(snapshot.reminders, target.reminders, cur.reminders) { reminders = target.reminders }
        go(snapshot.liveSort, target.liveSort, cur.liveSort) { write("livesort", target.liveSort) }
        val pinsChanged = go(snapshot.pinnedCats, target.pinnedCats, cur.pinnedCats) { pinnedCats = target.pinnedCats }
        val channelsChanged = go(snapshot.channels, target.channels, cur.channels) { channelCustom = target.channels }
        go(snapshot.parentPin, target.parentPin, cur.parentPin) { parentPin = target.parentPin }
        val accountChanged = go(snapshot.account, target.account, cur.account) { account = target.account }
        return SyncApplied(changes, accountChanged, profilesChanged, libraryChanged = settingsChanged || pinsChanged || channelsChanged)
    }

    /**
     * When the item behind a sync record was last changed here, if known: the time in the value itself for
     * history and favourites, otherwise the time of the last write to the store key it lives in.
     */
    fun syncChangedAt(recKey: String, v: JsonElement?): Long? {
        fun num(field: String): Long? = ((v as? JsonObject)?.get(field) as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }
        fun owner(prefix: String) = recKey.removePrefix(prefix).substringBefore(':')
        return when {
            recKey.startsWith("hist:") -> num("updated") ?: touched["hist_${owner("hist:")}"]
            recKey.startsWith("fav:") -> num("at") ?: touched["fav_${owner("fav:")}"]
            recKey.startsWith("favorder:") -> touched["fav_${owner("favorder:")}"]
            recKey.startsWith("set:") -> SettingsSplit.changedAt(read<Map<String, Long>>("settings_at", emptyMap()), recKey.removePrefix("set:"))
            recKey.startsWith("profile:") || recKey == "order:profiles" -> touched["profiles"]
            recKey.startsWith("trk:") -> touched["trk_${recKey.removePrefix("trk:")}"]
            recKey.startsWith("rem:") -> touched["reminders"]
            recKey.startsWith("livesort:") -> touched["livesort"]
            recKey == "pincats" -> touched["pincats"]
            recKey == "chcustom" -> touched["chcustom"]
            recKey == "parentpin" -> touched[PARENT_PIN]
            recKey == "account" -> touched["account"]
            recKey.startsWith("allow:") -> touched["allow_${owner("allow:")}"]
            recKey.startsWith("dis:") -> (v as? JsonPrimitive)?.content?.toLongOrNull() ?: touched["dis_${owner("dis:")}"]
            else -> null
        }
    }

    /**
     * This screen's profile [from] becomes the household's profile [into] (see [ProfileFold]): its
     * favourites, history and kids' time move to that id. Call on the main thread, before the first sync.
     */
    fun foldProfile(from: String, into: Profile) {
        if (from == into.id) return
        val mergedFavs: List<FavItem> = ProfileFold.favs(favs(into.id), favs(from))
        val mergedHistory: List<WatchEntry> = ProfileFold.history(history(into.id), history(from))
        val next: List<Profile> = profiles.map { if (it.id == from) into else it }.distinctBy { it.id }
        profiles = next
        if (mergedFavs.isNotEmpty()) write<List<FavItem>>("fav_${into.id}", mergedFavs)
        if (mergedHistory.isNotEmpty()) write<List<WatchEntry>>("hist_${into.id}", mergedHistory, history = true, sync = true)
        write<List<FavItem>?>("fav_$from", null)
        write<List<WatchEntry>?>("hist_$from", null)
        dismissed(from).takeIf { it.isNotEmpty() }?.let { m -> write("dis_${into.id}", Dismissals.merge(dismissed(into.id), m)) }
        write<Map<String, Long>?>("dis_$from", null)
        val e = prefs.edit()
        sumTodayUsage(prefs.getString("use_${into.id}", null), prefs.getString("use_$from", null), today())
            ?.let { e.putString("use_${into.id}", it) }
        prefs.getString("lastcat_$from", null)?.let { e.putString("lastcat_${into.id}", it) }
        e.remove("use_$from").remove("allow_$from").remove("lastcat_$from")
        if (prefs.getString("last_profile", null) == from) e.putString("last_profile", into.id)
        e.apply()
        saver.execute { dev.relay.tv.tv.WatchNext.clearProfile(appCtx, from) }
    }

    private fun removeProfileKeys(pid: String) {
        write<Map<String, Long>?>("dis_$pid", null)
        write<List<FavItem>?>("fav_$pid", null)
        write<List<WatchEntry>?>("hist_$pid", null)
        write<Map<String, String>?>("usx_$pid", null)
        val e = prefs.edit().remove("use_$pid").remove("allow_$pid").remove("lastcat_$pid")
        if (prefs.getString("last_profile", null) == pid) e.remove("last_profile")
        e.apply()
        saver.execute { dev.relay.tv.tv.WatchNext.clearProfile(appCtx, pid) }
    }

    fun deleteProfile(pid: String) {
        profiles = profiles.filterNot { it.id == pid }
        removeProfileKeys(pid)
    }
}
