package dev.relay.tv.data

/**
 * What Relay works out from a parsed library before showing it: languages, version keys, quality,
 * sort keys, HD flags and the three title orders. Pure, so the catalog's start-up path, the refresh
 * path and the tests all use the same code, and the saved copy (LibrarySnapshot) can be compared
 * with the result.
 */
object LibraryDerive {
    private val ADULT = Regex("(?i)(adult|xxx|18\\+|porn|for adults)")
    private val YEAR = Regex("[(\\[](19|20)(\\d{2})[)\\]]")

    /** True for provider categories that hold adult material, by their name. Saved with the library copy. */
    fun isAdult(categoryName: String): Boolean = ADULT.containsMatchIn(categoryName)

    /** "Film (2019)" -> "2019". Used when the provider sends no year. Saved with the library copy. */
    fun yearIn(name: String): String? = YEAR.find(name)?.let { it.groupValues[1] + it.groupValues[2] }

    /** Language group per provider category id; categories without a recognised language are left out. */
    fun categoryLangs(cats: List<Category>): Map<String, String> = buildMap {
        cats.forEach { c -> LanguageDetector.ofCategory(c.name)?.let { put(c.id, it) } }
    }

    class ChannelMaps(
        val lang: Map<Int, String>,
        val versionKeys: Map<Int, String>,
        val quality: Map<Int, Int>,
        val sortKeys: Map<Int, String>,
        val hd: Set<Int>,
        /** How many channels took their facts from the previous library instead of working them out. */
        val reused: Int = 0,
    )

    /** The library already installed, for a refresh to reuse from. Holds references only. */
    class PreviousChannels(val channels: List<Channel>, val catLang: Map<String, String>, val maps: ChannelMaps)

    /**
     * One pass over the channels: each name is cleaned once (see [ChannelText.derive]). With
     * [previous], a channel whose id, name and category language are the same as before takes
     * its facts from there; everything else is worked out. The result is always the same as
     * working out every channel (RefreshReuseTest). Ids the provider listed twice are always
     * worked out, because the old maps hold only one of the two.
     */
    fun channels(list: List<Channel>, liveCatLang: Map<String, String>, previous: PreviousChannels? = null): ChannelMaps {
        val lang = HashMap<Int, String>()
        val versionKeys = HashMap<Int, String>(list.size * 2)
        val quality = HashMap<Int, Int>(list.size * 2)
        val sortKeys = HashMap<Int, String>(list.size * 2)
        val hd = HashSet<Int>()
        val old = previous?.let { byUniqueId(it.channels) { c -> c.id } }
        var reused = 0
        for (c in list) {
            val catLang = liveCatLang[c.categoryId]
            val o = old?.get(c.id)
            val m = previous?.maps
            if (o != null && m != null && o.name == c.name && previous.catLang[o.categoryId] == catLang &&
                m.versionKeys.containsKey(c.id) && m.quality.containsKey(c.id) && m.sortKeys.containsKey(c.id)
            ) {
                m.lang[c.id]?.let { lang[c.id] = it }
                versionKeys[c.id] = m.versionKeys.getValue(c.id)
                quality[c.id] = m.quality.getValue(c.id)
                sortKeys[c.id] = m.sortKeys.getValue(c.id)
                if (c.id in m.hd) hd.add(c.id)
                reused++
            } else {
                val f = ChannelText.derive(c.name, catLang)
                f.lang?.let { lang[c.id] = it }
                versionKeys[c.id] = f.versionKey
                quality[c.id] = f.quality
                sortKeys[c.id] = f.sortKey
                if (f.hd) hd.add(c.id)
            }
        }
        return ChannelMaps(lang, versionKeys, quality, sortKeys, hd, reused)
    }

    /** Items by id, leaving out ids that appear more than once. */
    private fun <T> byUniqueId(list: List<T>, id: (T) -> Int): Map<Int, T> {
        val all = HashMap<Int, T>(list.size * 2)
        val twice = HashSet<Int>()
        for (t in list) if (all.put(id(t), t) != null) twice.add(id(t))
        twice.forEach { all.remove(it) }
        return all
    }

    class TitleLists<T>(
        val lang: Map<Int, String>,
        /** Newest first; ties keep library order. */
        val added: List<T>,
        /** Rated titles only, best first; ties keep library order. */
        val rated: List<T>,
        /** A to Z, case-insensitive; ties keep library order. */
        val byName: List<T>,
        /** How many titles took their language from the previous library. */
        val reused: Int = 0,
    )

    /** The movies (or series) already installed, for a refresh to reuse languages from. */
    class PreviousTitles<T>(val items: List<T>, val catLang: Map<String, String>, val lang: Map<Int, String>)

    private fun <T> titleLanguages(
        list: List<T>,
        catLang: Map<String, String>,
        previous: PreviousTitles<T>?,
        id: (T) -> Int,
        name: (T) -> String,
        category: (T) -> String,
    ): Pair<HashMap<Int, String>, Int> {
        val lang = HashMap<Int, String>()
        val old = previous?.let { byUniqueId(it.items, id) }
        var reused = 0
        for (v in list) {
            val now = catLang[category(v)]
            val o = old?.get(id(v))
            if (o != null && name(o) == name(v) && previous.catLang[category(o)] == now) {
                previous.lang[id(v)]?.let { lang[id(v)] = it }
                reused++
            } else {
                LanguageDetector.ofTitle(name(v), now)?.let { lang[id(v)] = it }
            }
        }
        return lang to reused
    }

    /** [afterLanguages] runs once the languages are done, for the timing log. */
    fun movies(
        list: List<Movie>,
        vodCatLang: Map<String, String>,
        previous: PreviousTitles<Movie>? = null,
        afterLanguages: () -> Unit = {},
    ): TitleLists<Movie> {
        val (lang, reused) = titleLanguages(list, vodCatLang, previous, { it.id }, { it.name }, { it.categoryId })
        afterLanguages()
        return TitleLists(
            lang = lang,
            added = list.sortedByDescending { it.added },
            rated = list.filter { it.rating > 0 }.sortedByDescending { it.rating },
            byName = sortedByLowerName(list) { it.name },
            reused = reused,
        )
    }

    fun series(
        list: List<Series>,
        seriesCatLang: Map<String, String>,
        previous: PreviousTitles<Series>? = null,
        afterLanguages: () -> Unit = {},
    ): TitleLists<Series> {
        val (lang, reused) = titleLanguages(list, seriesCatLang, previous, { it.id }, { it.name }, { it.categoryId })
        afterLanguages()
        return TitleLists(
            lang = lang,
            added = list.sortedByDescending { it.added },
            rated = list.filter { it.rating > 0 }.sortedByDescending { it.rating },
            byName = sortedByLowerName(list) { it.name },
            reused = reused,
        )
    }
}
