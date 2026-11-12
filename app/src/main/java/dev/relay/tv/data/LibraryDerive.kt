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
        val lang: HashMap<Int, String>,
        val versionKeys: HashMap<Int, String>,
        val quality: HashMap<Int, Int>,
        val sortKeys: HashMap<Int, String>,
        val hd: HashSet<Int>,
    )

    /** One pass over the channels: each name is cleaned once (see [ChannelText.derive]). */
    fun channels(list: List<Channel>, liveCatLang: Map<String, String>): ChannelMaps {
        val lang = HashMap<Int, String>()
        val versionKeys = HashMap<Int, String>(list.size * 2)
        val quality = HashMap<Int, Int>(list.size * 2)
        val sortKeys = HashMap<Int, String>(list.size * 2)
        val hd = HashSet<Int>()
        for (c in list) {
            val f = ChannelText.derive(c.name, liveCatLang[c.categoryId])
            f.lang?.let { lang[c.id] = it }
            versionKeys[c.id] = f.versionKey
            quality[c.id] = f.quality
            sortKeys[c.id] = f.sortKey
            if (f.hd) hd.add(c.id)
        }
        return ChannelMaps(lang, versionKeys, quality, sortKeys, hd)
    }

    class TitleLists<T>(
        val lang: HashMap<Int, String>,
        /** Newest first; ties keep library order. */
        val added: List<T>,
        /** Rated titles only, best first; ties keep library order. */
        val rated: List<T>,
        /** A to Z, case-insensitive; ties keep library order. */
        val byName: List<T>,
    )

    /** [afterLanguages] runs once the languages are done, for the timing log. */
    fun movies(list: List<Movie>, vodCatLang: Map<String, String>, afterLanguages: () -> Unit = {}): TitleLists<Movie> {
        val lang = HashMap<Int, String>()
        list.forEach { v -> LanguageDetector.ofTitle(v.name, vodCatLang[v.categoryId])?.let { lang[v.id] = it } }
        afterLanguages()
        return TitleLists(
            lang = lang,
            added = list.sortedByDescending { it.added },
            rated = list.filter { it.rating > 0 }.sortedByDescending { it.rating },
            byName = sortedByLowerName(list) { it.name },
        )
    }

    fun series(list: List<Series>, seriesCatLang: Map<String, String>, afterLanguages: () -> Unit = {}): TitleLists<Series> {
        val lang = HashMap<Int, String>()
        list.forEach { v -> LanguageDetector.ofTitle(v.name, seriesCatLang[v.categoryId])?.let { lang[v.id] = it } }
        afterLanguages()
        return TitleLists(
            lang = lang,
            added = list.sortedByDescending { it.added },
            rated = list.filter { it.rating > 0 }.sortedByDescending { it.rating },
            byName = sortedByLowerName(list) { it.name },
        )
    }
}
