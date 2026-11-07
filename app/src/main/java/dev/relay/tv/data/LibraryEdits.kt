package dev.relay.tv.data

/**
 * Library changes sent from the phone companion's editing page: which categories and countries
 * are hidden or pinned, live favourites in order, and channel names. Parsing is strict about
 * shape so a bad request changes nothing.
 */
data class LibraryChange(
    val hidden: Set<String>,
    val pinnedLive: List<String>,
    val pinnedVod: List<String>,
    val pinnedSeries: List<String>,
    /** Live favourite channel ids in their new order; null leaves favourites alone. */
    val favLive: List<Int>?,
    /** Channel id to name; an empty name goes back to the provider's. Null leaves names alone. */
    val renames: Map<Int, String>?,
)

object LibraryEdits {
    private val KEY = Regex("^(live|vod|series|livegroup):[^\\s]{1,80}$")
    private const val MAX_ITEMS = 20_000
    private const val MAX_NAME = 80

    fun parse(text: String): LibraryChange {
        val root = try {
            Jsons.json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not valid JSON")
        }
        fun strings(name: String): List<String> {
            val a = root.at(name) ?: return emptyList()
            val arr = a.arr() ?: throw IllegalArgumentException("$name must be a list")
            if (arr.size > MAX_ITEMS) throw IllegalArgumentException("$name is too long")
            return arr.map { it.str() ?: throw IllegalArgumentException("$name has an empty entry") }
        }
        val hidden = strings("hidden")
        hidden.firstOrNull { !KEY.matches(it) }?.let { throw IllegalArgumentException("Unknown category key: $it") }
        val favs = root.at("favLive")?.let { el ->
            val arr = el.arr() ?: throw IllegalArgumentException("favLive must be a list")
            arr.map { it.int() ?: throw IllegalArgumentException("favLive must hold channel ids") }
        }
        val renames = root.at("renames")?.let { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: throw IllegalArgumentException("renames must be an object")
            obj.entries.associate { (k, v) ->
                val id = k.toIntOrNull() ?: throw IllegalArgumentException("Bad channel id: $k")
                id to (v.str().orEmpty().trim().take(MAX_NAME))
            }
        }
        return LibraryChange(
            hidden = hidden.toSet(),
            pinnedLive = strings("pinnedLive").distinct(),
            pinnedVod = strings("pinnedVod").distinct(),
            pinnedSeries = strings("pinnedSeries").distinct(),
            favLive = favs,
            renames = renames,
        )
    }

    /**
     * Applies a new live favourites order. Only channels already in favourites are kept (the page
     * can remove and reorder, not add); movie and series favourites are untouched.
     */
    fun reorderFavs(current: List<FavItem>, liveOrder: List<Int>): List<FavItem> {
        val live = current.filter { it.kind == "live" }.associateBy { it.id }
        val ordered = liveOrder.distinct().mapNotNull { live[it] }
        return ordered + current.filter { it.kind != "live" }
    }

    /** Renames merged into the saved ones: an empty name removes the rename. */
    fun mergeRenames(current: Map<Int, String>, changes: Map<Int, String>): Map<Int, String> {
        val out = current.toMutableMap()
        for ((id, name) in changes) if (name.isEmpty()) out.remove(id) else out[id] = name
        return out
    }
}
