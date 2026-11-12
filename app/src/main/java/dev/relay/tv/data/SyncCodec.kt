package dev.relay.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest

/** A screen that takes part in sync, shown in Settings and kept in the document. */
@Serializable
data class Screen(val name: String, val seen: Long)

/**
 * What sync reads from and writes to the store, as plain values so the rules can be tested without
 * a device. Favourites and history are per profile id. Kids' time is "date|milliseconds": [useOwn] is
 * this screen's, [useOthers] the other screens' (profile, then screen).
 */
data class SyncData(
    val profiles: List<Profile> = emptyList(),
    val favs: Map<String, List<FavItem>> = emptyMap(),
    val history: Map<String, List<WatchEntry>> = emptyMap(),
    val settings: Settings = Settings(),
    /** When each shared setting was last changed on this screen. */
    val settingsAt: Map<String, Long> = emptyMap(),
    val tracks: Map<Int, TrackPref> = emptyMap(),
    val reminders: List<Reminder> = emptyList(),
    val liveSort: Map<String, Int> = emptyMap(),
    val pinnedCats: List<String> = emptyList(),
    val channels: ChannelCustom = ChannelCustom(),
    val parentPin: String? = null,
    val account: Account? = null,
    val useOwn: Map<String, String> = emptyMap(),
    val useOthers: Map<String, Map<String, String>> = emptyMap(),
    val allow: Map<String, Long> = emptyMap(),
    val screens: Map<String, Screen> = emptyMap(),
)

/**
 * Turns [SyncData] into the document's items and back. An item is named by what it is:
 * `profile:<id>`, `fav:<profile>:<kind>:<id>`, `hist:<profile>:<key>`, `set:<setting>`, `trk:<series>`,
 * `rem:<channel>:<start>`, `livesort:<category>`, `use:<profile>:<screen>`, `allow:<profile>`,
 * `screen:<screen>`, and the single items `order:profiles`, `favorder:<profile>`, `pincats`, `chcustom`,
 * `parentpin` and `account`. Something that is empty is left out rather than written, so a screen that
 * has never set it cannot overwrite another screen's value; emptying it later is recorded as a removal.
 */
object SyncCodec {
    private val json = Jsons.json

    private inline fun <reified T> enc(v: T): JsonElement = json.encodeToJsonElement(v)

    private inline fun <reified T> dec(e: JsonElement?): T? =
        if (e == null) null else try {
            json.decodeFromJsonElement<T>(e)
        } catch (ex: Exception) {
            null
        }

    private fun strings(e: JsonElement?): List<String> =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    private fun usage(day: String, ms: Long): JsonElement = JsonObject(mapOf("d" to JsonPrimitive(day), "ms" to JsonPrimitive(ms)))

    private fun usageString(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return null
        val day = (o["d"] as? JsonPrimitive)?.content ?: return null
        val ms = (o["ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        return DailyUsage.format(day, ms)
    }

    /** The current state as items. [dev] is this screen; [today] is "yyyy-MM-dd". */
    fun toRecords(d: SyncData, dev: String, today: String, now: Long): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val ids = d.profiles.map { it.id }.toSet()

        for (p in d.profiles) out["profile:${p.id}"] = enc(p)
        if (d.profiles.isNotEmpty()) out["order:profiles"] = JsonArray(d.profiles.map { JsonPrimitive(it.id) })

        for ((pid, list) in d.favs) {
            if (pid !in ids || list.isEmpty()) continue
            for (f in list) out["fav:$pid:${f.kind}:${f.id}"] = enc(f)
            out["favorder:$pid"] = JsonArray(list.map { JsonPrimitive("${it.kind}:${it.id}") })
        }
        for ((pid, list) in d.history) {
            if (pid !in ids) continue
            for (e in list) out["hist:$pid:${e.key}"] = enc(e)
        }

        val settings = SettingsSplit.toObject(d.settings)
        for (f in SettingsSplit.SHARED) settings[f]?.let { out["set:$f"] = it }

        for ((sid, t) in d.tracks) out["trk:$sid"] = enc(t)
        for (r in d.reminders) out["rem:${r.channelId}:${r.start}"] = enc(r)
        for ((cat, sort) in d.liveSort) out["livesort:$cat"] = JsonPrimitive(sort)
        if (d.pinnedCats.isNotEmpty()) out["pincats"] = JsonArray(d.pinnedCats.map { JsonPrimitive(it) })
        if (d.channels != ChannelCustom()) out["chcustom"] = enc(d.channels)
        if (!d.parentPin.isNullOrEmpty()) out["parentpin"] = JsonPrimitive(d.parentPin)
        d.account?.let { out["account"] = enc(it) }

        for ((pid, raw) in d.useOwn) {
            if (pid in ids && DailyUsage.ms(raw, today) > 0) out["use:$pid:$dev"] = usage(today, DailyUsage.ms(raw, today))
        }
        for ((pid, m) in d.useOthers) {
            if (pid !in ids) continue
            for ((other, raw) in m) {
                if (other != dev && DailyUsage.ms(raw, today) > 0) out["use:$pid:$other"] = usage(today, DailyUsage.ms(raw, today))
            }
        }
        for ((pid, until) in d.allow) if (pid in ids && until > now - DAY) out["allow:$pid"] = JsonPrimitive(until)

        for ((id, s) in d.screens) out["screen:$id"] = enc(s)
        return out
    }

    /**
     * [local] with everything the document says applied: the state every screen converges on. Per-screen
     * settings stay as they are. A removal in the document removes the thing here, except for the login,
     * which is never taken away by another screen signing out. Items that cannot be read are skipped.
     */
    fun fromRecords(recs: Map<String, SyncRec>, local: SyncData, dev: String, today: String): SyncData {
        fun live(prefix: String): List<Pair<String, JsonElement>> =
            recs.entries.filter { it.key.startsWith(prefix) && !it.value.gone }.map { it.key.removePrefix(prefix) to it.value.v!! }

        // Profiles, in the order the document gives.
        val order = strings(recs["order:profiles"]?.takeUnless { it.gone }?.v)
        val localIndex = local.profiles.withIndex().associate { it.value.id to it.index }
        val decoded = live("profile:").mapNotNull { (_, v) -> dec<Profile>(v) }
        val profiles = if (decoded.isEmpty()) local.profiles else decoded.sortedWith(
            compareBy<Profile>({ order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { localIndex[it.id] ?: Int.MAX_VALUE }, { it.id }),
        )
        val ids = profiles.map { it.id }.toSet()

        val favs = HashMap<String, List<FavItem>>()
        val history = HashMap<String, List<WatchEntry>>()
        for (pid in ids) {
            val items = live("fav:$pid:").mapNotNull { (_, v) -> dec<FavItem>(v) }
            if (items.isNotEmpty()) {
                val order2 = strings(recs["favorder:$pid"]?.takeUnless { it.gone }?.v)
                fun pos(f: FavItem) = order2.indexOf("${f.kind}:${f.id}")
                favs[pid] = items.sortedWith(
                    compareBy<FavItem>({ if (pos(it) < 0) 0 else 1 }, { pos(it) }).thenByDescending { it.at }.thenBy { it.kind }.thenBy { it.id },
                )
            }
            val entries = live("hist:$pid:").mapNotNull { (_, v) -> dec<WatchEntry>(v) }
            if (entries.isNotEmpty()) {
                history[pid] = entries.sortedWith(compareByDescending<WatchEntry> { it.updated }.thenBy { it.key }).take(SyncMerge.HISTORY_CAP)
            }
        }

        val settings = SettingsSplit.withShared(local.settings, live("set:").toMap())

        val tracks = live("trk:").mapNotNull { (k, v) -> k.toIntOrNull()?.let { id -> dec<TrackPref>(v)?.let { id to it } } }.toMap()
        val reminders = live("rem:").mapNotNull { (_, v) -> dec<Reminder>(v) }.sortedWith(compareBy({ it.start }, { it.channelId }))
        val liveSort = live("livesort:").mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }.toMap()

        val pincats = recs["pincats"]?.let { r -> if (r.gone) emptyList() else strings(r.v) } ?: local.pinnedCats
        val channels = recs["chcustom"]?.let { r -> if (r.gone) ChannelCustom() else dec<ChannelCustom>(r.v) ?: local.channels } ?: local.channels
        val parentPin = when (val r = recs["parentpin"]) {
            null -> local.parentPin
            else -> if (r.gone) null else (r.v as? JsonPrimitive)?.content ?: local.parentPin
        }
        val account = recs["account"]?.takeUnless { it.gone }?.let { dec<Account>(it.v) } ?: local.account

        val others = HashMap<String, MutableMap<String, String>>()
        for ((k, v) in live("use:")) {
            val pid = k.substringBefore(':')
            val owner = k.substringAfter(':')
            if (pid in ids && owner != dev) usageString(v)?.takeIf { DailyUsage.ms(it, today) > 0 }?.let { others.getOrPut(pid) { HashMap() }[owner] = it }
        }
        val allow = live("allow:").mapNotNull { (pid, v) ->
            if (pid in ids) (v as? JsonPrimitive)?.content?.toLongOrNull()?.let { pid to it } else null
        }.toMap()
        val screens = live("screen:").mapNotNull { (id, v) -> dec<Screen>(v)?.let { id to it } }.toMap()

        return local.copy(
            profiles = profiles, favs = favs, history = history, settings = settings, tracks = tracks,
            reminders = reminders, liveSort = liveSort, pinnedCats = pincats, channels = channels,
            parentPin = parentPin, account = account, useOthers = others, allow = allow, screens = screens,
        )
    }

    /** A short stable tag for a provider account: the same user name gives the same tag on every screen. */
    fun accountTag(a: Account?): String {
        val name = a?.username?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return ""
        return sha("relay-account:$name").take(12)
    }

    internal fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val DAY = 86_400_000L
}

/** What [Store.syncApply] did, for the log and for the app to react to. */
data class SyncApplied(
    val changes: Int,
    val accountChanged: Boolean,
    val profilesChanged: Boolean,
    /** Settings, pinned categories or channel edits changed, which the channel list may need to redo. */
    val libraryChanged: Boolean = false,
)

/** What this screen last agreed with the document: per item, its time, who set it and a short hash of the value. */
@Serializable
data class BaseRec(val at: Long, val dev: String, val h: String? = null)

/**
 * Works out what changed on this screen since the last sync by comparing the current items with what
 * was agreed then. Nothing in the app has to remember a removal: an item that was agreed and is gone
 * now becomes a removal here. The time of a change is the time it was made when the store knows it,
 * and always later than the version it replaces.
 */
object SyncDiff {

    /** Items that are never removed because another screen removed them: the login. */
    private val NEVER_GONE = setOf("account")

    fun hash(v: JsonElement): String = SyncCodec.sha(v.toString()).take(16)

    /**
     * This screen's view as records. [changedAt] gives the time a changed item was last changed here,
     * or null when it is not known; [isDefault] says a setting still has its factory value (such a
     * setting gets time 0 so it never beats a value another screen chose).
     */
    fun export(
        snapshot: Map<String, JsonElement>,
        base: Map<String, BaseRec>,
        dev: String,
        clock: SyncClock,
        now: Long,
        changedAt: (String, JsonElement?) -> Long?,
        isDefault: (String, JsonElement) -> Boolean = { _, _ -> false },
    ): Map<String, SyncRec> {
        val out = HashMap<String, SyncRec>(snapshot.size + 16)
        for ((k, v) in snapshot) {
            val b = base[k]
            if (b != null && b.h == hash(v)) {
                out[k] = SyncRec(b.at, b.dev, v)
                continue
            }
            val known = changedAt(k, v)
            val at = when {
                known != null -> later(b, known, clock)
                b == null && isDefault(k, v) -> 0L
                else -> later(b, clock.next(now), clock)
            }
            out[k] = SyncRec(at, dev, v)
        }
        for ((k, b) in base) {
            if (k in snapshot) continue
            if (b.h == null) {
                out[k] = SyncRec(b.at, b.dev, null)
            } else if (k !in NEVER_GONE && !k.startsWith("use:")) {
                out[k] = SyncRec(later(b, changedAt(k, null) ?: clock.next(now), clock), dev, null)
            }
        }
        return out
    }

    private fun later(b: BaseRec?, at: Long, clock: SyncClock): Long {
        val t = maxOf(at, (b?.at ?: 0L) + 1)
        clock.observe(t)
        return t
    }

    /**
     * What this screen holds after the document was applied: each item's time and who set it, with the
     * hash of the value as this screen stores it (so a value another version wrote with extra fields
     * is not mistaken for a local change). Items this screen does not know are left out.
     */
    fun rebase(merged: Map<String, SyncRec>, after: Map<String, JsonElement>): Map<String, BaseRec> {
        val out = HashMap<String, BaseRec>(merged.size)
        for ((k, r) in merged) {
            if (r.gone) {
                out[k] = BaseRec(r.at, r.dev, null)
            } else {
                val v = after[k] ?: continue
                out[k] = BaseRec(r.at, r.dev, hash(v))
            }
        }
        return out
    }

    /**
     * Like [rebase], for a document that was applied to the store. An item counts as agreed only if
     * what the store holds now is what the merge meant to put there ([intended]). If the viewer changed
     * it while the sync was running, it keeps the agreement it had before ([old]), so the next sync
     * sees it as a change made here instead of losing it.
     */
    fun rebaseChecked(
        merged: Map<String, SyncRec>,
        after: Map<String, JsonElement>,
        intended: Map<String, JsonElement>,
        old: Map<String, BaseRec>,
    ): Map<String, BaseRec> {
        val out = HashMap<String, BaseRec>(merged.size)
        for ((k, r) in merged) {
            if (r.gone) {
                out[k] = BaseRec(r.at, r.dev, null)
                continue
            }
            val now = after[k]
            val meant = intended[k]
            when {
                now != null && meant != null && hash(now) == hash(meant) -> out[k] = BaseRec(r.at, r.dev, hash(now))
                old[k] != null -> out[k] = old[k]!!
            }
        }
        return out
    }

    /** The factory value of a setting item, for [export]'s `isDefault`. */
    fun settingDefault(key: String, value: JsonElement): Boolean {
        if (!key.startsWith("set:")) return false
        return SettingsSplit.toObject(Settings())[key.removePrefix("set:")] == value
    }
}
