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
    val DEFAULT_FEATURED = listOf(EN, HI, BN)

    fun label(key: String?): String = when (key) {
        EN -> "English"
        HI -> "Hindi"
        BN -> "Bengali"
        SOUTH -> "South Indian"
        BD -> "Bangladesh"
        OTHER -> "Other languages"
        else -> "Unclassified"
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
class LangRank(private val order: List<String>) {
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
    private val CATEGORY_WORDS: List<Pair<String, List<String>>> = listOf(
        Langs.BD to listOf("BANGLADESH", "BANGLADESHI", "BD", "DHAKA"),
        Langs.SOUTH to listOf(
            "SOUTH INDIAN", "SOUTH INDIA", "SOUTH MOVIES", "SOUTH DUBBED", "SOUTH HINDI", "TAMIL", "TELUGU",
            "MALAYALAM", "KANNADA", "KOLLYWOOD", "MOLLYWOOD", "SANDALWOOD",
        ),
        Langs.BN to listOf("BANGLA", "BENGALI", "KOLKATA", "KOLKATTA", "WEST BENGAL"),
        Langs.HI to listOf("HINDI", "BOLLYWOOD"),
        Langs.EN to listOf("ENGLISH", "HOLLYWOOD"),
        Langs.OTHER to listOf(
            "ARABIC", "SPANISH", "LATINO", "FRENCH", "GERMAN", "TURKISH", "PORTUGUESE", "ITALIAN", "POLISH",
            "PUNJABI", "URDU", "PAKISTAN", "PAKISTANI", "MARATHI", "GUJARATI", "KOREAN", "JAPANESE", "CHINESE",
            "FILIPINO", "PERSIAN", "RUSSIAN", "DUTCH", "NEPALI", "SINHALA",
        ),
    )

    private val CHANNEL_WORDS: List<Pair<String, List<String>>> = listOf(
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
        Langs.EN to listOf("ENGLISH"),
    )

    private val NON_WORD = Regex("[^A-Z0-9&]+")

    /** " STAR JALSHA HD " style text for whole-word matching. */
    private fun words(s: String): String = " " + ProviderNames.clean(s).uppercase().replace(NON_WORD, " ").trim() + " "

    private fun has(w: String, key: String): Boolean = w.contains(" " + key.uppercase().replace(NON_WORD, " ").trim() + " ")

    private fun firstMatch(text: String, table: List<Pair<String, List<String>>>): String? {
        val w = words(text)
        for ((lang, keys) in table) if (keys.any { has(w, it) }) return lang
        return null
    }

    /** Language group of a short code such as "HI", "SO-IN" or "UK"; null when unknown. */
    fun ofCode(code: String?): String? = code?.uppercase()?.let { CODES[it] }

    /** A provider category: words in its name, else its country or language prefix. */
    fun ofCategory(name: String): String? {
        firstMatch(name, CATEGORY_WORDS)?.let { return it }
        val prefix = Regex("^\\s*[\\[(|]?\\s*([A-Za-z][A-Za-z\\-]{1,9})\\s*[\\])|]?\\s*[:|\\-–»]").find(name)?.groupValues?.get(1)
        return ofCode(prefix)
    }

    /** A movie or series: its title tag wins, then its category. */
    fun ofTitle(rawTitle: String, categoryLang: String?): String? =
        ofCode(TitleParts.parse(rawTitle).tag) ?: categoryLang

    /** A live channel: known channel names, then its category, then its country tag. */
    fun ofChannel(name: String, categoryLang: String?): String? {
        firstMatch(name, CHANNEL_WORDS)?.let { return it }
        if (categoryLang != null) return categoryLang
        val tag = Regex("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|]?\\s*[:|\\-]").find(name)?.groupValues?.get(1)
        return ofCode(tag)
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
