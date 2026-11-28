package dev.relay.tv.data

/**
 * Language groups Relay ranks by. Bengali means Indian Bengali; Bangladesh is its own group, as is
 * South Indian (even when dubbed into Hindi). Everything else is "other", and unclassified titles
 * stay null.
 */
object Langs {
    const val EN = "en"
    const val HI = "hi"
    const val BN = "bn"
    const val SOUTH = "south"
    const val BD = "bd"
    const val OTHER = "other"

    val GROUPS = listOf(EN, HI, BN, SOUTH, BD)
    val DEFAULT_ORDER = listOf(EN, HI, BN, SOUTH, BD)

    fun label(key: String?): String = when (key) {
        EN -> "English"
        HI -> "Hindi"
        BN -> "Bengali"
        SOUTH -> "South Indian"
        BD -> "Bangladesh"
        OTHER -> "Other languages"
        else -> "Unclassified"
    }

    /** Order for the "By language" view: the profile's order, with Bangladesh right under Bengali. */
    fun viewOrder(order: List<String>): List<String> {
        val base = (order + GROUPS).distinct().filter { it != BD }.toMutableList()
        val i = base.indexOf(BN)
        if (i >= 0) base.add(i + 1, BD) else base.add(BD)
        return base
    }

    /** Audio and subtitle track codes for a group, for the player's ordered preferences. */
    fun trackCodes(key: String): List<String> = when (key) {
        EN -> listOf("en")
        HI -> listOf("hi")
        BN, BD -> listOf("bn")
        SOUTH -> listOf("ta", "te", "ml", "kn")
        else -> emptyList()
    }
}

/** Ranks a language group by a profile's order: its groups first, then unclassified, then others. */
data class LangRank(val order: List<String>) {
    private val index = order.withIndex().associate { it.value to it.index }
    fun rank(lang: String?): Int = when {
        lang == null -> order.size
        else -> index[lang] ?: (order.size + 1)
    }
}

/**
 * Works out a title's or channel's language group from the names providers use: title tags
 * ("HI - ", "SO-IN - "), category names ("HINDI MOVIES", "BANGLADESH"), channel names
 * ("Star Jalsha", "Channel i") and country tags ("UK:", "BD:").
 */
object LanguageDetector {

    private val CODES: Map<String, String> = buildMap {
        listOf("EN", "ENG", "EN-US", "EN-GB", "UK", "US", "USA", "GB", "AU", "AUS", "IE", "NZ", "ENGLISH").forEach { put(it, Langs.EN) }
        listOf("HI", "HIN", "HINDI", "IN", "IND", "IN-HI", "HI-IN", "BOLLY", "BOLLYWOOD").forEach { put(it, Langs.HI) }
        listOf("BN", "BEN", "BENGALI", "BANGLA", "BN-IN", "IN-BN", "KOL", "WB").forEach { put(it, Langs.BN) }
        listOf("BD", "BN-BD", "BD-BN", "BGD", "BANGLADESH").forEach { put(it, Langs.BD) }
        listOf(
            "SO-IN", "SOUTH", "S-IN", "TA", "TAM", "TAMIL", "TE", "TEL", "TELUGU", "ML", "MAL", "MALAYALAM",
            "KN", "KAN", "KANNADA", "TA-IN", "TE-IN", "ML-IN", "KN-IN",
        ).forEach { put(it, Langs.SOUTH) }
        listOf(
            "ES", "ESP", "LAT", "LATINO", "FR", "FRA", "DE", "GER", "IT", "ITA", "PT", "BR", "NL", "PL", "RU", "TR",
            "TUR", "AR", "ARA", "ARABIC", "KO", "KR", "JA", "JP", "ZH", "CN", "PA", "PB", "PUN", "PUNJABI", "MR",
            "MARATHI", "GU", "GUJARATI", "UR", "URDU", "PK", "NE", "NP", "SI", "LK", "TH", "VI", "VN", "ID", "FA",
            "IR", "HE", "IL", "EL", "GR", "SV", "SE", "NO", "DA", "DK", "FI", "HU", "RO", "CS", "CZ", "BG", "HR",
            "SR", "RS", "AL", "EX-YU", "EXYU", "PH", "MY", "AF", "SO", "AM",
        ).forEach { put(it, Langs.OTHER) }
    }

    // Order matters: the first group whose words appear wins.
    internal val CATEGORY_WORDS: List<Pair<String, List<String>>> = listOf(
        Langs.BD to listOf("BANGLADESH", "BANGLADESHI", "BD", "DHAKA"),
        Langs.SOUTH to listOf(
            "SOUTH INDIAN", "SOUTH INDIA", "SOUTH MOVIES", "SOUTH DUBBED", "SOUTH HINDI", "TAMIL", "TELUGU",
            "MALAYALAM", "KANNADA", "KOLLYWOOD", "MOLLYWOOD", "SANDALWOOD",
        ),
        Langs.BN to listOf("BANGLA", "BENGALI", "KOLKATA", "KOLKATTA", "WEST BENGAL"),
        Langs.EN to listOf(
            "ENGLISH", "HOLLYWOOD", "HINDI DUBBED", "HINDI DUB", "DUBBED IN HINDI", "DUAL AUDIO",
            "ENGLISH DUBBED",
        ),
        Langs.HI to listOf("HINDI", "BOLLYWOOD"),
        Langs.OTHER to listOf(
            "ARABIC", "SPANISH", "LATINO", "FRENCH", "GERMAN", "TURKISH", "PORTUGUESE", "ITALIAN", "POLISH",
            "PUNJABI", "URDU", "PAKISTAN", "PAKISTANI", "MARATHI", "GUJARATI", "KOREAN", "JAPANESE", "CHINESE",
            "FILIPINO", "PERSIAN", "RUSSIAN", "DUTCH", "NEPALI", "SINHALA",
        ),
    )

    internal val CHANNEL_WORDS: List<Pair<String, List<String>>> = listOf(
        // Language words in a channel's own name beat brand guesses ("NTV TELUGU" is not Bangladeshi NTV).
        Langs.SOUTH to listOf("TAMIL", "TELUGU", "MALAYALAM", "KANNADA", "KERALAM"),
        Langs.BD to listOf(
            "CHANNEL I", "CHANNEL 9", "CHANNEL 24", "NTV", "RTV", "ATN BANGLA", "ATN NEWS", "SOMOY", "JAMUNA",
            "EKATTOR", "EKHON", "INDEPENDENT TV", "BOISHAKHI", "BANGLAVISION", "DESH TV", "GAZI TV", "GTV",
            "MAASRANGA", "BTV", "DBC NEWS", "DEEPTO", "NAGORIK", "MOHONA", "ASIAN TV", "SA TV", "BIJOY TV",
            "DURONTO", "ANANDA TV", "BANGLADESH",
        ),
        Langs.BN to listOf(
            "JALSHA", "ZEE BANGLA", "COLORS BANGLA", "SUN BANGLA", "AAKASH AATH", "SONY AATH", "ABP ANANDA",
            "KOLKATA TV", "R PLUS", "NEWS18 BANGLA", "DD BANGLA", "ENTER 10 BANGLA", "SANGEET BANGLA", "TV9 BANGLA",
            "24 GHANTA", "CALCUTTA NEWS", "RUPOSHI BANGLA", "BANGLA", "BENGALI",
        ),
        Langs.SOUTH to listOf(
            "SUN TV", "SUN MUSIC", "KTV", "STAR VIJAY", "VIJAY TV", "GEMINI", "ASIANET", "STAR MAA", "MAA TV",
            "KAIRALI", "MAZHAVIL", "SURYA TV", "UDAYA", "POLIMER", "THANTHI", "PUTHIYA THALAIMURAI", "KALAIGNAR",
            "JAYA TV", "RAJ TV", "FLOWERS TV", "MANORAMA",
        ),
        Langs.HI to listOf(
            "STAR PLUS", "STAR BHARAT", "STAR GOLD", "STAR UTSAV", "SONY SAB", "SONY TV", "SONY MAX", "SONY PAL",
            "SET INDIA", "ZEE TV", "ZEE CINEMA", "ZEE ANMOL", "ZEE ACTION", "&TV", "AND TV", "&PICTURES", "COLORS TV",
            "COLORS CINEPLEX", "COLORS RISHTEY", "AAJ TAK", "ABP NEWS", "NDTV INDIA", "INDIA TV", "ZEE NEWS",
            "REPUBLIC BHARAT", "B4U", "DANGAL", "SHEMAROO", "NEWS18 INDIA", "TV9 BHARATVARSH", "HINDI",
        ),
        Langs.EN to listOf(
            "ENGLISH", "STAR MOVIES", "SONY PIX", "MOVIES NOW", "MNX", "ROMEDY NOW", "&FLIX", "AND FLIX",
            "HBO", "WION", "TIMES NOW", "NDTV 24X7", "REPUBLIC TV", "CNN NEWS18", "INDIA TODAY", "MIRROR NOW",
            "DISCOVERY", "NATIONAL GEOGRAPHIC", "NAT GEO", "CARTOON NETWORK", "POGO", "NICKELODEON",
            "DISNEY CHANNEL", "STAR SPORTS", "SONY SPORTS", "TEN SPORTS", "CNN", "BBC", "ESPN",
        ),
    )

    private val NON_WORD = Regex("[^A-Z0-9&]+")
    private val DUBBED_TITLE_WORDS = listOf(
        " HINDI DUBBED ", " HINDI DUB ", " DUBBED IN HINDI ", " DUAL AUDIO ",
        " ENGLISH DUBBED ", " HINDI & ENG ", " ENG & HINDI ",
    )
    private val SOUTH_WORDS = listOf(
        " SOUTH ", " TAMIL ", " TELUGU ", " MALAYALAM ", " KANNADA ",
        " KOLLYWOOD ", " MOLLYWOOD ", " SANDALWOOD ",
    )

    // Compiled once. These were built inside ofCategory and ofChannel, so every call compiled them again.
    private val CATEGORY_PREFIX = Regex("^\\s*[\\[(|]?\\s*([A-Za-z][A-Za-z\\-]{1,9})\\s*[\\])|]?\\s*[:|\\-–»]")
    private val CHANNEL_PREFIX = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-]")

    /** " STAR JALSHA HD " style text for whole-word matching, from a name that is already cleaned. */
    private fun wordsOfClean(clean: String): String = " " + clean.uppercase().replace(NON_WORD, " ").trim() + " "

    // Keys normalised once, not per name: this runs for every channel and title in the playlist.
    private fun prepare(table: List<Pair<String, List<String>>>): List<Pair<String, List<String>>> =
        table.map { (lang, keys) -> lang to keys.map { " " + it.uppercase().replace(NON_WORD, " ").trim() + " " } }

    private val CATEGORY_KEYS by lazy { prepare(CATEGORY_WORDS) }
    private val CHANNEL_KEYS by lazy { prepare(CHANNEL_WORDS) }

    private fun firstMatch(clean: String, table: List<Pair<String, List<String>>>): String? {
        val w = wordsOfClean(clean)
        for ((lang, keys) in table) for (k in keys) if (w.contains(k)) return lang
        return null
    }

    /** Language group of a short code such as "HI", "SO-IN" or "UK"; null when unknown. */
    fun ofCode(code: String?): String? = code?.uppercase()?.let { CODES[it] }

    /** Maps an ISO 639-1 / 639-2 code or language name (plus optional country) from TMDB/MDBList to a Relay group. */
    fun fromApi(language: String?, country: String? = null): String? {
        val lang = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (lang == "none" || lang == "und" || lang == "xx" || lang == "zxx") return null
        val ctry = country?.trim()?.uppercase()
        return when (lang) {
            "en", "eng", "english" -> Langs.EN
            "hi", "hin", "hindi" -> Langs.HI
            "bn", "ben", "bengali", "bangla" ->
                if (ctry == "BD" || ctry == "BGD" || ctry == "BANGLADESH") Langs.BD else Langs.BN
            "ta", "tam", "tamil", "te", "tel", "telugu",
            "ml", "mal", "malayalam", "kn", "kan", "kannada" -> Langs.SOUTH
            else -> Langs.OTHER
        }
    }

    /** True when the title or category name marks a dubbed or dual-audio track. */
    fun isDubbed(title: String, categoryName: String? = null): Boolean {
        val tw = wordsOfClean(ProviderNames.clean(title))
        if (tw.contains(" DUBBED ") || tw.contains(" HINDI DUB ") || tw.contains(" DUAL AUDIO ")) return true
        if (categoryName != null) {
            val cw = wordsOfClean(ProviderNames.clean(categoryName))
            if (cw.contains(" DUBBED ") || cw.contains(" HINDI DUB ") || cw.contains(" DUAL AUDIO ") ||
                (cw.contains(" HOLLYWOOD ") && cw.contains(" HINDI ")) ||
                (cw.contains(" SOUTH ") && cw.contains(" HINDI "))
            ) {
                return true
            }
        }
        return false
    }

    /** A provider category: words in its name, else its country or language prefix. */
    fun ofCategory(name: String): String? {
        firstMatch(ProviderNames.clean(name), CATEGORY_KEYS)?.let { return it }
        return ofCode(CATEGORY_PREFIX.find(name)?.groupValues?.get(1))
    }

    /** A movie or series: Hindi-dubbed Hollywood titles map to EN (preserving South Indian), else tag then category. */
    fun ofTitle(rawTitle: String, categoryLang: String?): String? {
        val tag = ofCode(TitleParts.tagOf(rawTitle))
        val w = wordsOfClean(ProviderNames.clean(rawTitle))
        if (DUBBED_TITLE_WORDS.any { w.contains(it) } &&
            tag != Langs.SOUTH &&
            categoryLang != Langs.SOUTH &&
            SOUTH_WORDS.none { w.contains(it) }
        ) {
            return Langs.EN
        }
        return tag ?: categoryLang
    }

    /** A live channel: known channel names, then its category, then its country tag. */
    fun ofChannel(name: String, categoryLang: String?): String? =
        ofChannelCleaned(name, ProviderNames.clean(name), categoryLang)

    /** As [ofChannel], for a caller that has already run [ProviderNames.clean] on [name]. */
    internal fun ofChannelCleaned(name: String, clean: String, categoryLang: String?): String? {
        firstMatch(clean, CHANNEL_KEYS)?.let { return it }
        if (categoryLang != null) return categoryLang
        return ofCode(CHANNEL_PREFIX.find(name)?.groupValues?.get(1))
    }
}

/** What Relay recognised in the library, for Settings → Diagnostics → Language report. */
data class LanguageReport(
    val channels: Map<String?, Int>,
    val movies: Map<String?, Int>,
    val series: Map<String?, Int>,
    val unknownTags: List<Pair<String, Int>>,
    val unknownCategories: List<Pair<String, Int>>,
) {
    fun text(): String = buildString {
        fun section(name: String, m: Map<String?, Int>) {
            val total = m.values.sum()
            append("$name ($total)\n")
            (Langs.GROUPS + Langs.OTHER + listOf<String?>(null)).forEach { k ->
                val n = m[k] ?: 0
                if (n > 0) append("  ${Langs.label(k)}: $n (${n * 100 / total.coerceAtLeast(1)}%)\n")
            }
        }
        section("Channels", channels)
        section("Movies", movies)
        section("Series", series)
        if (unknownTags.isNotEmpty()) {
            append("Title tags not recognised:\n")
            unknownTags.forEach { (t, n) -> append("  $t: $n\n") }
        }
        if (unknownCategories.isNotEmpty()) {
            append("Categories not recognised (largest first):\n")
            unknownCategories.forEach { (c, n) -> append("  $c: $n\n") }
        }
    }
}
