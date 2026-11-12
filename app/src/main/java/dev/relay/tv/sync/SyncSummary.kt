package dev.relay.tv.sync

import dev.relay.tv.data.SyncRec

/** Plain-words counts of what a sync changed, for the log and the status line. Pure. */
object SyncSummary {

    private class Kind(val prefix: String, val one: String, val many: String)

    // Order is the order they are listed in.
    private val KINDS = listOf(
        Kind("hist:", "watched title", "watched titles"),
        Kind("fav:", "favourite", "favourites"),
        Kind("set:", "setting", "settings"),
        Kind("profile:", "profile", "profiles"),
        Kind("trk:", "audio and subtitle choice", "audio and subtitle choices"),
        Kind("rem:", "reminder", "reminders"),
        Kind("livesort:", "channel sort choice", "channel sort choices"),
        Kind("pincats", "pinned categories change", "pinned categories changes"),
        Kind("chcustom", "channel edit", "channel edits"),
        Kind("parentpin", "parent PIN", "parent PINs"),
        Kind("account", "login", "logins"),
        Kind("allow:", "extra viewing time", "extra viewing times"),
    )

    /** Keys whose records differ between [a] and [b], leaving out bookkeeping a viewer would not recognise. */
    fun changedKeys(a: Map<String, SyncRec>, b: Map<String, SyncRec>): List<String> {
        val keys = (a.keys + b.keys).filter { a[it] != b[it] }
        return keys.filter { k -> KINDS.any { k.startsWith(it.prefix) } }.sorted()
    }

    /**
     * "3 watched titles, 1 favourite from Living room TV" for the records of [merged] that differ from
     * [before]; empty when nothing a viewer would notice changed. The screen named is the one that made
     * most of the changes, looked up with [nameOf].
     */
    fun describe(merged: Map<String, SyncRec>, before: Map<String, SyncRec>, nameOf: (String) -> String?): String {
        val keys = changedKeys(merged, before)
        if (keys.isEmpty()) return ""
        val parts = KINDS.mapNotNull { kind ->
            val n = keys.count { it.startsWith(kind.prefix) }
            if (n == 0) null else "$n ${if (n == 1) kind.one else kind.many}"
        }
        val who = keys.mapNotNull { (merged[it] ?: before[it])?.dev?.takeIf { d -> d.isNotEmpty() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let(nameOf)
        return parts.joinToString(", ") + (who?.let { " from $it" } ?: "")
    }
}
