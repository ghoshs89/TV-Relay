package dev.relay.tv.data

/**
 * Providers often list one channel several times ("UK: Sky News HD", "UK: Sky News FHD",
 * "UK: Sky News HEVC"). These helpers spot such versions so lists show one entry, playback can
 * fall back to another version, and the chooser can rank them. Pure, so it is unit tested.
 */
object ChannelVersions {

    private val LEAD = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-–]\\s*")
    private val QUALITY = Regex(
        "(?i)(?<![A-Z0-9])(4K|UHD|FHD|HD|SD|HQ|LQ|HEVC|H\\.?265|H\\.?264|X265|50\\s?FPS|60\\s?FPS|2160P|1080P|1080I|720P|576P|480P|BACKUP|BKP|ALT|ALTERNATE|RAW)(?![A-Z0-9])",
    )
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Same key means the same channel in another quality: the country tag stays (UK and US
     * feeds differ), quality words go, numbers stay ("Star Sports 1" is not "Star Sports 2").
     */
    fun key(name: String): String {
        val clean = ProviderNames.clean(name)
        val m = LEAD.find(clean)
        val tag = m?.groupValues?.get(1)?.uppercase().orEmpty()
        val rest = (if (m != null) clean.substring(m.range.last + 1) else clean)
            .replace(QUALITY, " ")
            .lowercase()
            .replace(NON_ALNUM, "")
        return if (rest.isEmpty()) clean.lowercase().replace(NON_ALNUM, "") else "$tag:$rest"
    }

    // Raw name too: cleaning drops "1080p" style tokens and turns superscripts into letters.
    private fun words(name: String): String =
        " " + (name + " " + ProviderNames.clean(name)).uppercase().replace(NON_ALNUM, " ") + " "

    /** Higher is better: 4K 40, FHD 30, HD 20, unmarked 15, SD 0; backups and alternates lose 5. */
    fun quality(name: String): Int {
        val u = words(name)
        fun has(vararg w: String) = w.any { u.contains(" $it ") }
        var q = when {
            has("4K", "UHD", "2160P") -> 40
            has("FHD", "1080P", "1080I") -> 30
            has("HD", "720P", "HQ") -> 20
            has("SD", "LQ", "576P", "480P") -> 0
            else -> 15
        }
        if (has("BACKUP", "BKP", "ALT", "ALTERNATE")) q -= 5
        return q
    }

    /** Short description for the versions chooser, e.g. "FHD · HEVC" or "Standard". */
    fun describe(name: String): String {
        val u = words(name)
        fun has(vararg w: String) = w.any { u.contains(" $it ") }
        val parts = buildList {
            when {
                has("4K", "UHD", "2160P") -> add("4K")
                has("FHD", "1080P", "1080I") -> add("Full HD")
                has("HD", "720P", "HQ") -> add("HD")
                has("SD", "LQ", "576P", "480P") -> add("SD")
            }
            if (has("HEVC", "H265", "X265")) add("HEVC")
            if (has("50FPS", "60FPS", "50 FPS", "60 FPS")) add("50/60 fps")
            if (has("BACKUP", "BKP", "ALT", "ALTERNATE")) add("Backup")
        }
        return parts.joinToString(" · ").ifEmpty { "Standard" }
    }

    /** Your chosen version first, then best quality; ties keep the provider's order. */
    fun <T> order(items: List<T>, name: (T) -> String, id: (T) -> Int, preferred: Int?): List<T> {
        // Quality is worked out once per item, not on every comparison.
        val q = IntArray(items.size) { quality(name(items[it])) }
        return items.indices
            .sortedWith(compareBy<Int>({ if (id(items[it]) == preferred) 0 else 1 }, { -q[it] }, { it }))
            .map { items[it] }
    }

    /**
     * Shows each channel once: the first time a version appears in [list], the best version
     * present in the list takes its place; later versions are dropped. Items without a key stay.
     * [rankOf] is the item's place among its versions (lower is better), worked out in advance.
     */
    fun <T> collapse(list: List<T>, keyOf: (T) -> String?, rankOf: (T) -> Int, id: (T) -> Int, preferred: (String) -> Int?): List<T> {
        val best = HashMap<String, T>()
        val seenTwice = HashSet<String>()
        for (t in list) {
            val k = keyOf(t) ?: continue
            val cur = best[k]
            if (cur == null) {
                best[k] = t
                continue
            }
            seenTwice += k
            val p = preferred(k)
            val better = when {
                id(cur) == p -> false
                id(t) == p -> true
                else -> rankOf(t) < rankOf(cur)
            }
            if (better) best[k] = t
        }
        if (seenTwice.isEmpty()) return list
        val emitted = HashSet<String>()
        val out = ArrayList<T>(list.size)
        for (t in list) {
            val k = keyOf(t)
            when {
                k == null || k !in seenTwice -> out += t
                emitted.add(k) -> out += best.getValue(k)
            }
        }
        return out
    }

    /** Groups channels into versions; only keys with two or more channels are returned. */
    fun <T> group(items: List<T>, keyOf: (T) -> String): Map<String, List<T>> =
        items.groupBy(keyOf).filterValues { it.size > 1 }
}
