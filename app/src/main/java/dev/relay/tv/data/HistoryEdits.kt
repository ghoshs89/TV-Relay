package dev.relay.tv.data

/** Removing entries from the watch history ("Continue watching", "Recently watched"). Pure, so the rules can be tested. */
object HistoryEdits {
    /** The list without the entry that has this key; every other entry stays as it is. */
    fun without(list: List<WatchEntry>, key: String): List<WatchEntry> = list.filterNot { it.key == key }

    /** The list without any channel entry for this stream (a channel can be recorded under more than one key). */
    fun withoutChannel(list: List<WatchEntry>, streamId: String): List<WatchEntry> =
        list.filterNot { it.kind == "live" && it.streamId == streamId }
}
