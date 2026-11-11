package dev.relay.tv.data

/** Text rules for channel names that lists use to sort and filter. Pure, so they can be worked out once per load. */
object ChannelKeys {
    private val CHANNEL_TAG = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-]\\s*")
    private val HD = Regex("(?i)\\b(HD|FHD|UHD|4K|HEVC|H265)\\b")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]+")

    /** "CA: CBC CALGARY" -> ("CA", "CBC CALGARY"). */
    fun split(name: String): Pair<String?, String> {
        val m = CHANNEL_TAG.find(name) ?: return null to name
        val rest = name.substring(m.range.last + 1).trim()
        return if (rest.isEmpty()) null to name else m.groupValues[1].uppercase() to rest
    }

    /** What A to Z sorting compares: the name without its country tag, lower case, letters and digits only. */
    fun sortKey(name: String): String = split(name).second.lowercase().replace(NON_ALNUM, "").trim()

    /** True for names that mark HD, Full HD, 4K or HEVC streams. */
    fun isHd(name: String): Boolean = HD.containsMatchIn(name)
}
