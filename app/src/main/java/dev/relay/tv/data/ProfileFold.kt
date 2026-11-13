package dev.relay.tv.data

/**
 * When a screen joins sync, its own profiles and the household's are put together by id. A fresh
 * install makes its own "Me" with a new id, so without this the household ends up with two profiles
 * of the same name. Before the first sync, a profile here that is plainly the same one as a profile
 * in the household (same name, same kind, no PIN of its own that differs) takes that profile's id,
 * and its favourites and history go with it. Pure, so it is unit tested on a computer.
 */
object ProfileFold {
    private fun nameKey(p: Profile) = p.name.trim().lowercase()

    /**
     * Which of this screen's profiles ([mine]) become which household profile ([theirs]): this
     * screen's id to the household profile. A profile is only matched when exactly one household
     * profile fits and no other profile here has claimed it; anything unclear is left alone.
     */
    fun plan(mine: List<Profile>, theirs: List<Profile>): Map<String, Profile> {
        val myIds = mine.map { it.id }.toSet()
        val theirIds = theirs.map { it.id }.toSet()
        val out = LinkedHashMap<String, Profile>()
        val taken = HashSet<String>()
        for (p in mine) {
            if (p.id in theirIds) continue
            val fits = theirs.filter { t ->
                t.id !in myIds && t.id !in taken && nameKey(t) == nameKey(p) && t.kids == p.kids &&
                    (p.pin.isNullOrEmpty() || p.pin == t.pin)
            }
            // Two profiles here with the same name can't both be the household's one: leave them alone.
            val twins = mine.count { it.id !in theirIds && nameKey(it) == nameKey(p) && it.kids == p.kids }
            if (fits.size == 1 && twins == 1) {
                out[p.id] = fits[0]
                taken += fits[0].id
            }
        }
        return out
    }

    /** Favourites of both, the first list's order first, each title once. */
    fun favs(into: List<FavItem>, from: List<FavItem>): List<FavItem> =
        (into + from).distinctBy { it.kind to it.id }

    /** History of both, newest first, each title once (its newest entry), at most [max]. */
    fun history(into: List<WatchEntry>, from: List<WatchEntry>, max: Int = 400): List<WatchEntry> =
        (into + from).sortedByDescending { it.updated }.distinctBy { it.key }.take(max)
}
