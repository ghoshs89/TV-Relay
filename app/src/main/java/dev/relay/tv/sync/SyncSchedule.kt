package dev.relay.tv.sync

/** When a sync may run. Pure, so the waits and the skips are tested. */
object SyncSchedule {
    /** After a change to favourites, shared settings, profiles or a removal. */
    const val DEBOUNCE_MS = 30_000L

    /** While the app is open. */
    const val PERIODIC_MS = 10L * 60_000L

    /** After the app starts, so the first screen is not slowed. */
    const val START_DELAY_MS = 4_000L

    /** 1, 5, then 15 minutes after failures. */
    fun backoffMs(failures: Int): Long = when {
        failures <= 0 -> 0L
        failures == 1 -> 60_000L
        failures == 2 -> 5L * 60_000L
        else -> 15L * 60_000L
    }

    enum class Decision {
        /** Go. */
        Run,

        /** A run is under way already. */
        Busy,

        /** The library is being read; a slow TV should not also be syncing. */
        Parsing,

        /** A recent failure; wait. */
        Backoff,
    }

    /** A viewer's "Sync now" ignores the backoff and the parsing skip, never a run already under way. */
    fun decide(now: Long, running: Boolean, parsing: Boolean, failures: Int, lastFailAt: Long, manual: Boolean): Decision = when {
        running -> Decision.Busy
        manual -> Decision.Run
        parsing -> Decision.Parsing
        failures > 0 && now < lastFailAt + backoffMs(failures) -> Decision.Backoff
        else -> Decision.Run
    }

    /** Failures that are worth retrying by themselves; the others need the viewer. */
    fun failure(result: SyncResult): Boolean = when (result) {
        SyncResult.Offline, SyncResult.Refused, SyncResult.NotFound, SyncResult.Problem -> true
        else -> false
    }
}

/** The status line in Settings. Pure. */
object SyncStatusText {
    fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> (s / 60).let { if (it == 1L) "1 minute ago" else "$it minutes ago" }
            s < 86_400 -> (s / 3600).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            else -> (s / 86_400).let { if (it == 1L) "1 day ago" else "$it days ago" }
        }
    }

    fun line(result: SyncResult?, message: String, lastOk: Long, now: Long): String {
        val last = if (lastOk > 0) ", last in sync ${ago(now - lastOk)}" else ""
        return when (result) {
            null -> "Not synced yet"
            SyncResult.UpToDate, SyncResult.Pulled, SyncResult.Pushed, SyncResult.Both -> "In sync, ${ago(now - lastOk)}"
            SyncResult.Offline -> "Waiting for the network$last"
            SyncResult.Refused -> "The sync token was refused. Paste a new one from your phone$last"
            SyncResult.NotFound -> "The sync repository can't be found with this token$last"
            SyncResult.WrongKey -> "This screen's sync key doesn't open the file. Join again from a screen that works$last"
            SyncResult.WrongAccount -> "This screen is signed in to a different provider account$last"
            SyncResult.NeedsUpdate -> "Update Relay on this screen to keep syncing$last"
            SyncResult.Problem -> (message.ifBlank { "Sync hit a problem" }) + last
        }
    }
}

/** Which of the local safety copies to keep. Pure. */
object SyncBackups {
    const val KEEP = 3
    const val PREFIX = "before-sync-"

    fun fileName(now: Long) = "$PREFIX$now.json"

    /** The names to delete so that only the newest [KEEP] stay; names that are not ours are never listed. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }
            .sortedByDescending { it.removePrefix(PREFIX).removeSuffix(".json").toLongOrNull() ?: 0L }
            .drop(keep)
}
