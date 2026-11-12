package dev.relay.tv.sync

import dev.relay.tv.data.BaseRec
import dev.relay.tv.data.SealException
import dev.relay.tv.data.SyncApplied
import dev.relay.tv.data.SyncClock
import dev.relay.tv.data.SyncDiff
import dev.relay.tv.data.SyncDoc
import dev.relay.tv.data.SyncDocException
import dev.relay.tv.data.SyncMerge
import dev.relay.tv.data.SyncRec
import dev.relay.tv.data.SyncSeal
import dev.relay.tv.data.WrongAccountException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** What this screen remembers between runs. Never holds the key, the token or any content. */
@Serializable
data class SyncState(
    /** Per item, what this screen last agreed with the file. */
    val base: Map<String, BaseRec> = emptyMap(),
    /** The latest change time issued or seen (see [SyncClock]). */
    val clock: Long = 0L,
    /** Tag of the file as this screen last saw it. */
    val version: String? = null,
    /** When this screen last told the file it exists; refreshed rarely so it does not cause writes. */
    val screenSeen: Long = 0L,
    val lastOk: Long = 0L,
    /** False until the first run has completed on this screen. */
    val synced: Boolean = false,
    val lastBackup: Long = 0L,
)

interface SyncStateStore {
    fun load(): SyncState
    fun save(state: SyncState)
}

/** What applying the merged file to the store gave. */
class Applied(
    /** The items as the store holds them after writing. */
    val after: Map<String, JsonElement>,
    /** The items the merge meant the store to hold. */
    val intended: Map<String, JsonElement>,
    val summary: SyncApplied? = null,
)

/**
 * The screen's own data, as seen by the engine. The real one reads and writes [dev.relay.tv.data.Store];
 * tests use a plain map, so the whole loop is tested without a device.
 */
interface SyncLocal {
    val deviceId: String

    /** Tag of this screen's provider login, or "" when it has none yet. */
    fun accountTag(): String

    /** The current state as items, including this screen's own entry (seen at [screenSeen]). */
    fun items(screenSeen: Long): Map<String, JsonElement>

    /** When the item was last changed here, if known (see [dev.relay.tv.data.SyncDiff.export]). */
    fun changedAt(key: String, v: JsonElement?): Long?

    /** True for a setting that still has its factory value. */
    fun isDefault(key: String, v: JsonElement): Boolean

    /** Writes what the merge decided. Only items still as [items] saw them are touched. */
    fun apply(merged: Map<String, SyncRec>): Applied

    /** Keeps a copy of the data before sync changes it. */
    fun backup(reason: String)
}

/** A screen listed in the sync file. */
data class ScreenInfo(val id: String, val name: String, val seen: Long)

enum class SyncResult {
    UpToDate, Pulled, Pushed, Both,
    Offline, Refused, NotFound, WrongKey, WrongAccount, NeedsUpdate, Problem,
}

class SyncOutcome(
    val result: SyncResult,
    /** A sentence for the viewer when something went wrong. */
    val message: String = "",
    /** "3 watched titles, 1 favourite from Living room TV" */
    val pulled: String = "",
    val pushed: String = "",
    val applied: SyncApplied? = null,
    /** Size of the file written, 0 if none. */
    val bytes: Int = 0,
    /** The screens the file knows, newest first (this one included). */
    val screens: List<ScreenInfo> = emptyList(),
) {
    val ok: Boolean get() = result == SyncResult.UpToDate || result == SyncResult.Pulled || result == SyncResult.Pushed || result == SyncResult.Both
}

/**
 * One sync: read the file, open it, work out what changed here, merge, write back if the merge has
 * something the file lacked, and apply to this screen if the file had something it lacked. If another
 * screen wrote in between, it starts again (at most [MAX_ATTEMPTS] times). A wrong key, a damaged
 * file or another provider account stops it before anything is written or changed.
 */
class SyncEngine(
    private val local: SyncLocal,
    private val transport: SyncTransport,
    private val key: ByteArray,
    private val state: SyncStateStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val today: () -> String = { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) },
) {

    fun run(): SyncOutcome {
        repeat(MAX_ATTEMPTS) {
            once()?.let { return it }
        }
        return SyncOutcome(SyncResult.Problem, "Other screens kept changing the sync file. Relay will try again soon.")
    }

    private fun failed(e: TransportException) = SyncOutcome(
        when (e.problem) {
            Problem.Offline -> SyncResult.Offline
            Problem.Refused -> SyncResult.Refused
            Problem.NotFound -> SyncResult.NotFound
            else -> SyncResult.Problem
        },
        e.message.orEmpty(),
    )

    /** One attempt; null means another screen wrote meanwhile and the caller should try again. */
    private fun once(): SyncOutcome? {
        val st = state.load()
        val clock = SyncClock(st.clock)
        val t = now()
        val day = today()

        val remote = try {
            transport.read()
        } catch (e: TransportException) {
            return failed(e)
        }
        val remoteDoc = if (remote == null) null else try {
            SyncDoc.parse(String(SyncSeal.open(remote.bytes, key), Charsets.UTF_8))
        } catch (e: SealException) {
            val newer = e.message.orEmpty().contains("newer Relay")
            return SyncOutcome(
                when {
                    newer -> SyncResult.NeedsUpdate
                    e.wrongKey -> SyncResult.WrongKey
                    else -> SyncResult.Problem
                },
                e.message.orEmpty(),
            )
        } catch (e: SyncDocException) {
            return SyncOutcome(SyncResult.Problem, e.message.orEmpty())
        }
        if (remoteDoc != null && remoteDoc.newer) {
            return SyncOutcome(SyncResult.NeedsUpdate, "Update Relay on this screen to keep syncing. The sync file was written by a newer version.")
        }
        remoteDoc?.let { clock.observe(SyncMerge.latest(it)) }

        val seen = if (!st.synced || t - st.screenSeen > SEEN_REFRESH_MS) t else st.screenSeen
        val items = local.items(seen)
        val exported = SyncDiff.export(items, st.base, local.deviceId, clock, t, local::changedAt, local::isDefault)
        val localDoc = SyncDoc(account = local.accountTag(), recs = exported)
        val merged = try {
            SyncMerge.merge(localDoc, remoteDoc ?: SyncDoc(), t, day)
        } catch (e: WrongAccountException) {
            return SyncOutcome(SyncResult.WrongAccount, "This screen is signed in to a different provider account than the other screens.")
        }
        clock.observe(SyncMerge.latest(merged))

        val push = remoteDoc == null || SyncMerge.differences(merged, remoteDoc) > 0
        val pull = SyncMerge.differences(merged, localDoc) > 0
        val names = { dev: String -> screenName(merged.recs, dev) }
        val pulledText = if (pull) SyncSummary.describe(merged.recs, localDoc.recs, names) else ""
        val pushedText = if (push) SyncSummary.describe(merged.recs, remoteDoc?.recs.orEmpty(), names) else ""

        var lastBackup = st.lastBackup
        if ((push || pull) && (!st.synced || (pulledText.isNotEmpty() && t - st.lastBackup > BACKUP_EVERY_MS))) {
            local.backup(if (!st.synced) "before the first sync" else "before changes from another screen")
            lastBackup = t
        }

        var version = remote?.version
        var bytes = 0
        if (push) {
            val sealed = SyncSeal.seal(merged.encode().toByteArray(Charsets.UTF_8), key)
            if (sealed.size > MAX_BYTES) {
                return SyncOutcome(SyncResult.Problem, "The sync file has grown too large (${sealed.size / 1024} KB). Remove old history on a screen and try again.")
            }
            when (val w = try {
                transport.write(sealed, remote?.version)
            } catch (e: TransportException) {
                return failed(e)
            }) {
                is WriteResult.Saved -> {
                    version = w.version
                    bytes = sealed.size
                }
                WriteResult.Changed -> {
                    state.save(st.copy(clock = clock.last))
                    return null
                }
            }
        }

        val applied = if (pull) local.apply(merged.recs) else null
        // Items the apply did not report on (this screen's own entry, say) are as they were.
        val base = SyncDiff.rebaseChecked(
            merged.recs,
            if (applied == null) items else items + applied.after,
            if (applied == null) items else items + applied.intended,
            st.base,
        )
        state.save(
            SyncState(
                base = base, clock = clock.last, version = version, screenSeen = seen, lastOk = t,
                synced = true, lastBackup = lastBackup,
            ),
        )

        val result = when {
            pulledText.isNotEmpty() && pushedText.isNotEmpty() -> SyncResult.Both
            pushedText.isNotEmpty() -> SyncResult.Pushed
            pulledText.isNotEmpty() -> SyncResult.Pulled
            else -> SyncResult.UpToDate
        }
        return SyncOutcome(
            result, pulled = pulledText, pushed = pushedText, applied = applied?.summary, bytes = bytes,
            screens = screensOf(merged.recs),
        )
    }

    /**
     * Takes this screen (or [id]) off the list of screens in the file. Best effort, one attempt: the data
     * stays, only the entry goes. Another screen still holding the key re-adds itself when it syncs.
     */
    fun forget(id: String): Boolean {
        return try {
            val remote = transport.read() ?: return true
            val doc = SyncDoc.parse(String(SyncSeal.open(remote.bytes, key), Charsets.UTF_8))
            if (doc.newer) return false
            val rec = doc.recs["screen:$id"] ?: return true
            if (rec.gone) return true
            val clock = SyncClock(maxOf(state.load().clock, SyncMerge.latest(doc)))
            val gone = SyncRec(clock.next(now()), local.deviceId, null)
            val next = doc.copy(recs = doc.recs + ("screen:$id" to gone))
            transport.write(SyncSeal.seal(next.encode().toByteArray(Charsets.UTF_8), key), remote.version) is WriteResult.Saved
        } catch (e: Exception) {
            false
        }
    }

    private fun screensOf(recs: Map<String, SyncRec>): List<ScreenInfo> = recs.entries
        .filter { it.key.startsWith("screen:") && !it.value.gone }
        .mapNotNull { (k, r) ->
            val o = r.v as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val seen = (o["seen"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            ScreenInfo(k.removePrefix("screen:"), name, seen)
        }
        .sortedWith(compareByDescending<ScreenInfo> { it.seen }.thenBy { it.id })

    private fun screenName(recs: Map<String, SyncRec>, dev: String): String? =
        ((recs["screen:$dev"]?.v as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content

    companion object {
        const val MAX_ATTEMPTS = 3

        /** GitHub's contents API carries files up to 1 MB; base64 adds a third. */
        const val MAX_BYTES = 700_000

        /** A screen tells the file it is still here this often at most, so quiet screens do not cause writes. */
        const val SEEN_REFRESH_MS = 6L * 3_600_000L
        const val BACKUP_EVERY_MS = 6L * 3_600_000L
    }
}
