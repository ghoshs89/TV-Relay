package dev.relay.tv.data

/**
 * FROZEN. A copy of the channel and title text rules as they were in release 1.0.94, before plan 4
 * made them cheaper. Never edit this file to make a test pass: it is the reference that
 * [ChannelFactsGoldenTest] holds the live code to. The only live pieces it leans on are the
 * keyword tables, [ProviderNames.clean] and [TitleParts] (plan 4 did not touch those).
 *
 * If a later change to the live rules is meant to change answers, bump `LibrarySnapshot.LOGIC`,
 * update the golden test's expectations on purpose and leave this file alone as history.
 */
object LegacyText {
    private val NON_WORD = Regex("[^A-Z0-9&]+")
    private val LEAD = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-–]\\s*")
    private val QUALITY = Regex(
        "(?i)(?<![A-Z0-9])(4K|UHD|FHD|HD|SD|HQ|LQ|HEVC|H\\.?265|H\\.?264|X265|50\\s?FPS|60\\s?FPS|2160P|1080P|1080I|720P|576P|480P|BACKUP|BKP|ALT|ALTERNATE|RAW)(?![A-Z0-9])",
    )
    private val VERSION_NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val CHANNEL_TAG = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-]\\s*")
    private val HD = Regex("(?i)\\b(HD|FHD|UHD|4K|HEVC|H265)\\b")
    private val KEY_NON_ALNUM = Regex("[^\\p{L}\\p{N} ]+")

    private fun words(s: String): String = " " + ProviderNames.clean(s).uppercase().replace(NON_WORD, " ").trim() + " "

    private fun prepare(table: List<Pair<String, List<String>>>): List<Pair<String, List<String>>> =
        table.map { (lang, keys) -> lang to keys.map { " " + it.uppercase().replace(NON_WORD, " ").trim() + " " } }

    private val CATEGORY_KEYS by lazy { prepare(LanguageDetector.CATEGORY_WORDS) }
    private val CHANNEL_KEYS by lazy { prepare(LanguageDetector.CHANNEL_WORDS) }

    private fun firstMatch(text: String, table: List<Pair<String, List<String>>>): String? {
        val w = words(text)
        for ((lang, keys) in table) for (k in keys) if (w.contains(k)) return lang
        return null
    }

    fun ofCategory(name: String): String? {
        firstMatch(name, CATEGORY_KEYS)?.let { return it }
        val prefix = Regex("^\\s*[\\[(|]?\\s*([A-Za-z][A-Za-z\\-]{1,9})\\s*[\\])|]?\\s*[:|\\-–»]").find(name)?.groupValues?.get(1)
        return LanguageDetector.ofCode(prefix)
    }

    fun ofTitle(rawTitle: String, categoryLang: String?): String? =
        LanguageDetector.ofCode(TitleParts.tagOf(rawTitle)) ?: categoryLang

    fun ofChannel(name: String, categoryLang: String?): String? {
        firstMatch(name, CHANNEL_KEYS)?.let { return it }
        if (categoryLang != null) return categoryLang
        val tag = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-]").find(name)?.groupValues?.get(1)
        return LanguageDetector.ofCode(tag)
    }

    fun key(name: String): String {
        val clean = ProviderNames.clean(name)
        val m = LEAD.find(clean)
        val tag = m?.groupValues?.get(1)?.uppercase().orEmpty()
        val rest = (if (m != null) clean.substring(m.range.last + 1) else clean)
            .replace(QUALITY, " ")
            .lowercase()
            .replace(VERSION_NON_ALNUM, "")
        return if (rest.isEmpty()) clean.lowercase().replace(VERSION_NON_ALNUM, "") else "$tag:$rest"
    }

    private fun versionWords(name: String): String =
        " " + (name + " " + ProviderNames.clean(name)).uppercase().replace(VERSION_NON_ALNUM, " ") + " "

    fun quality(name: String): Int {
        val u = versionWords(name)
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

    fun describe(name: String): String {
        val u = versionWords(name)
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

    private fun split(name: String): Pair<String?, String> {
        val m = CHANNEL_TAG.find(name) ?: return null to name
        val rest = name.substring(m.range.last + 1).trim()
        return if (rest.isEmpty()) null to name else m.groupValues[1].uppercase() to rest
    }

    fun sortKey(name: String): String = split(name).second.lowercase().replace(KEY_NON_ALNUM, "").trim()

    fun isHd(name: String): Boolean = HD.containsMatchIn(name)

    /** What the old ChannelStage worked out for one channel, one call at a time. */
    fun facts(name: String, categoryLang: String?): ChannelFacts {
        val lang = ofChannel(name, categoryLang)
        return ChannelFacts(
            lang = lang,
            versionKey = key(name) + "|" + (lang ?: ""),
            quality = quality(name),
            sortKey = sortKey(name),
            hd = isHd(name),
        )
    }
}
