package dev.relay.tv.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One synced value: when it last changed, on which screen, and the value. A value of null means the
 * thing was removed (a "tombstone"), which is what stops a removed favourite or watched title from
 * coming back from a screen that has not heard yet.
 */
data class SyncRec(
    val at: Long,
    val dev: String,
    val v: JsonElement? = null,
    /** Fields a newer version of Relay put on the record; carried through untouched. */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val gone: Boolean get() = v == null

    private fun signature(): String = (v?.toString() ?: "") + extra.toString()

    /**
     * True when this record wins over [o]: the later change, then the higher screen id, then the
     * fuller value (so a copy that kept a newer version's extra fields beats one that lost them),
     * then plain text order. Total, so every screen picks the same winner whatever the order.
     */
    fun beats(o: SyncRec): Boolean {
        if (at != o.at) return at > o.at
        if (dev != o.dev) return dev > o.dev
        val a = signature()
        val b = o.signature()
        if (a.length != b.length) return a.length > b.length
        return a > b
    }

    fun toJson(): JsonObject = JsonObject(
        buildMap {
            putAll(extra)
            put("at", JsonPrimitive(at))
            put("dev", JsonPrimitive(dev))
            if (v != null) put("v", v)
        },
    )

    companion object {
        private val KNOWN = setOf("at", "dev", "v")

        fun fromJson(o: JsonObject): SyncRec {
            val v = o["v"]?.takeUnless { it is JsonNull }
            return SyncRec(
                at = (o["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                dev = (o["dev"] as? JsonPrimitive)?.content.orEmpty(),
                v = v,
                extra = o.filterKeys { it !in KNOWN },
            )
        }
    }
}

class SyncDocException(message: String) : Exception(message)

/** The file kept in the store is a different provider account from this screen's. */
class WrongAccountException(message: String = "Different provider account") : Exception(message)

/**
 * The whole synced state as one document: a map from item (see [SyncCodec]) to its latest record.
 * [account] ties it to one provider account so two households never mix. [format] is the version of
 * this layout; a document with a higher number than this app understands is read but not written back.
 */
data class SyncDoc(
    val format: Int = FORMAT,
    val account: String = "",
    val recs: Map<String, SyncRec> = emptyMap(),
    /** Top-level fields from a newer version, carried through. */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val newer: Boolean get() = format > FORMAT

    fun toJson(): JsonObject = JsonObject(
        buildMap {
            putAll(extra)
            put("app", JsonPrimitive(APP))
            put("format", JsonPrimitive(format))
            put("account", JsonPrimitive(account))
            put("recs", JsonObject(recs.mapValues { it.value.toJson() }))
        },
    )

    fun encode(): String = toJson().toString()

    companion object {
        const val APP = "relay-sync"
        const val FORMAT = 1
        private val KNOWN = setOf("app", "format", "account", "recs")

        fun fromJson(o: JsonObject): SyncDoc {
            if ((o["app"] as? JsonPrimitive)?.content != APP) throw SyncDocException("This is not a Relay sync file.")
            val format = (o["format"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: throw SyncDocException("The sync file has no version.")
            val recs = (o["recs"] as? JsonObject).orEmpty().mapNotNull { (k, e) ->
                (e as? JsonObject)?.let { k to SyncRec.fromJson(it) }
            }.toMap()
            return SyncDoc(
                format = format,
                account = (o["account"] as? JsonPrimitive)?.content.orEmpty(),
                recs = recs,
                extra = o.filterKeys { it !in KNOWN },
            )
        }

        fun parse(text: String): SyncDoc {
            val root = try {
                Jsons.json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw SyncDocException("The sync file is damaged.")
            return fromJson(root)
        }
    }
}

/** Combining two copies of the document. Pure, so the rules are tested without a device. */
object SyncMerge {
    const val TOMBSTONE_MS = 180L * 86_400_000L
    const val HISTORY_CAP = 400

    /**
     * Every item from both copies; where both have one, the later change. The same whichever copy is
     * first, the same when repeated, and the same however three copies are grouped. Throws
     * [WrongAccountException] when the copies belong to different provider accounts.
     */
    fun union(a: SyncDoc, b: SyncDoc): SyncDoc {
        val account = when {
            a.account.isBlank() -> b.account
            b.account.isBlank() || a.account == b.account -> a.account
            else -> throw WrongAccountException()
        }
        val recs = HashMap<String, SyncRec>(a.recs.size + b.recs.size)
        recs.putAll(a.recs)
        for ((k, r) in b.recs) {
            val mine = recs[k]
            if (mine == null || r.beats(mine)) recs[k] = r
        }
        val extra = HashMap<String, JsonElement>(a.extra)
        for ((k, e) in b.extra) {
            val mine = extra[k]
            if (mine == null || e.toString() > mine.toString()) extra[k] = e
        }
        return SyncDoc(maxOf(a.format, b.format), account, recs, extra)
    }

    /**
     * Housekeeping after a merge: old tombstones go, today's kids viewing time is kept and older days
     * are dropped, everything that belonged to a deleted profile goes, and each profile keeps its
     * [HISTORY_CAP] most recent watched items.
     */
    fun tidy(doc: SyncDoc, now: Long, today: String): SyncDoc {
        val deleted = doc.recs.filter { (k, r) -> k.startsWith("profile:") && r.gone }.keys.map { it.removePrefix("profile:") }.toSet()
        val kept = HashMap<String, SyncRec>(doc.recs.size)
        val history = HashMap<String, MutableList<Pair<String, SyncRec>>>()
        for ((k, r) in doc.recs) {
            if (r.gone && r.at < now - TOMBSTONE_MS) continue
            val owner = ownerOf(k)
            if (owner != null && owner in deleted) continue
            if (k.startsWith("use:")) {
                val day = (r.v as? JsonObject)?.get("d")?.let { (it as? JsonPrimitive)?.content }
                if (r.gone || day != today) continue
            }
            kept[k] = r
            if (k.startsWith("hist:") && !r.gone && owner != null) history.getOrPut(owner) { mutableListOf() }.add(k to r)
        }
        for ((_, list) in history) {
            if (list.size <= HISTORY_CAP) continue
            list.sortWith(compareByDescending<Pair<String, SyncRec>> { it.second.at }.thenBy { it.first })
            for (i in HISTORY_CAP until list.size) kept.remove(list[i].first)
        }
        return doc.copy(recs = kept)
    }

    fun merge(a: SyncDoc, b: SyncDoc, now: Long, today: String): SyncDoc = tidy(union(a, b), now, today)

    /** The profile an item belongs to, for the kinds that belong to one. */
    private fun ownerOf(key: String): String? {
        for (p in OWNED) {
            if (key.startsWith(p)) return key.substring(p.length).substringBefore(':')
        }
        return null
    }

    private val OWNED = listOf("hist:", "fav:", "favorder:", "use:", "allow:", "dis:")

    /** The newest time on any record, for [SyncClock.observe]. */
    fun latest(doc: SyncDoc): Long = doc.recs.values.maxOfOrNull { it.at } ?: 0L

    /** Number of records that differ between two documents (used to decide whether a write is needed). */
    fun differences(a: SyncDoc, b: SyncDoc): Int {
        var n = 0
        for ((k, r) in a.recs) if (b.recs[k] != r) n++
        for (k in b.recs.keys) if (k !in a.recs) n++
        if (a.account != b.account || a.format != b.format || a.extra != b.extra) n++
        return n
    }
}
