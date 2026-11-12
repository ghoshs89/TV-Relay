package dev.relay.tv.sync

import android.content.Context
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Jsons
import dev.relay.tv.data.SyncSeal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Where this screen keeps its sync set-up: the repository, the token and the household key (held like
 * the provider password, never in a backup or the log), and, in a file of its own, what it last agreed
 * with the sync file. Nothing here exists until sync is set up from the phone page or by joining.
 */
class SyncSettings(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("relay_sync", Context.MODE_PRIVATE)
    private val stateFile = File(ctx.filesDir, "sync-state.json")

    class Config(val repo: Repo, val token: String, val key: ByteArray)

    var config: Config?
        get() {
            val repo = prefs.getString("repo", null)?.let { Repo.parse(it) } ?: return null
            val token = prefs.getString("token", null)?.takeIf { it.isNotBlank() } ?: return null
            val key = prefs.getString("key", null)?.let { SyncSeal.keyFromText(it) } ?: return null
            return Config(repo, token, key)
        }
        set(c) {
            val e = prefs.edit()
            if (c == null) {
                e.remove("repo").remove("token").remove("key")
            } else {
                e.putString("repo", c.repo.toString()).putString("token", c.token).putString("key", SyncSeal.keyToText(c.key))
            }
            // commit(): the set-up must be on disk before the first run starts.
            e.commit()
        }

    /** Replaces only the token (it expired, or was remade); the repository and key stay. */
    fun replaceToken(token: String) {
        prefs.edit().putString("token", token).commit()
    }

    val enabled: Boolean get() = config != null

    /** Values that must never reach a log line. */
    fun secrets(): List<String> {
        val t = prefs.getString("token", null)
        val k = prefs.getString("key", null)
        return listOfNotNull(t?.takeIf { it.length >= 8 }, k?.takeIf { it.length >= 8 })
    }

    val stateStore = object : SyncStateStore {
        @Synchronized
        override fun load(): SyncState = try {
            if (stateFile.exists()) Jsons.json.decodeFromString<SyncState>(stateFile.readText()) else SyncState()
        } catch (e: Exception) {
            AppLog.w("Sync", "The sync state could not be read; starting it again", e)
            SyncState()
        }

        @Synchronized
        override fun save(state: SyncState) {
            try {
                val tmp = File(ctx.filesDir, "sync-state.json.tmp")
                tmp.writeText(Jsons.json.encodeToString(state))
                if (!tmp.renameTo(stateFile)) {
                    stateFile.delete()
                    tmp.renameTo(stateFile)
                }
            } catch (e: Exception) {
                AppLog.w("Sync", "The sync state could not be saved", e)
            }
        }
    }

    /** Forgets the set-up on this screen. The data stays, only the connection goes. */
    fun clear() {
        config = null
        stateFile.delete()
        File(ctx.filesDir, "sync-state.json.tmp").delete()
    }
}
