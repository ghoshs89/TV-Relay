package dev.relay.tv.sync

import android.os.Handler
import android.os.Looper
import dev.relay.tv.BuildConfig
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Screen
import dev.relay.tv.data.SyncCodec
import dev.relay.tv.data.SyncData
import dev.relay.tv.data.SyncDiff
import dev.relay.tv.data.SyncRec
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Runs [block] on the main thread and waits for it; sync itself runs on a background thread. */
internal fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val task = FutureTask(Callable { block() })
    Handler(Looper.getMainLooper()).post(task)
    return task.get(30, TimeUnit.SECONDS)
}

/**
 * The engine's view of this screen, backed by the store. One of these lives for one run: it keeps the
 * snapshot it read so that applying only touches what is still as it was.
 */
class StoreSyncLocal(private val app: RelayApp, private val screenName: String) : SyncLocal {
    private val store = app.store
    private var snapshot: SyncData? = null

    override val deviceId: String get() = onMain { store.deviceId }

    override fun accountTag(): String = SyncCodec.accountTag(onMain { store.account })

    private fun own(d: SyncData, seen: Long) = d.copy(screens = mapOf(deviceId to Screen(screenName, seen)))

    private var seenAt = 0L

    override fun items(screenSeen: Long): Map<String, JsonElement> {
        seenAt = screenSeen
        val (snap, day, dev) = onMain {
            Triple(own(store.syncSnapshot(), screenSeen), store.today(), store.deviceId)
        }
        snapshot = snap
        return SyncCodec.toRecords(snap, dev, day, System.currentTimeMillis())
    }

    override fun changedAt(key: String, v: JsonElement?): Long? = onMain { store.syncChangedAt(key, v) }

    override fun isDefault(key: String, v: JsonElement): Boolean = SyncDiff.settingDefault(key, v)

    override fun apply(merged: Map<String, SyncRec>): Applied {
        val (snap, day, dev) = onMain {
            val s = snapshot ?: own(store.syncSnapshot(), seenAt)
            Triple(s, store.today(), store.deviceId)
        }
        val target = own(SyncCodec.fromRecords(merged, snap, dev, day), seenAt)
        // Writing happens on the main thread, where screens read the store.
        val (summary, now) = onMain {
            val done = store.syncApply(snap, target)
            done to own(store.syncSnapshot(), seenAt)
        }
        val t = System.currentTimeMillis()
        return Applied(
            after = SyncCodec.toRecords(now, deviceId, day, t),
            intended = SyncCodec.toRecords(target, deviceId, day, t),
            summary = summary,
        )
    }

    override fun backup(reason: String) {
        try {
            val dir = File(app.filesDir, "sync-backups").apply { mkdirs() }
            val json = onMain { store.exportJson(BuildConfig.VERSION_NAME) }
            File(dir, SyncBackups.fileName(System.currentTimeMillis())).writeText(json)
            SyncBackups.toDelete(dir.list()?.toList().orEmpty()).forEach { File(dir, it).delete() }
            AppLog.i("Sync", "Kept a local copy of the data $reason")
        } catch (e: Exception) {
            // A copy that could not be made must not block syncing; the data is changed only by merging.
            AppLog.w("Sync", "Could not keep a local copy $reason", e)
        }
    }
}
