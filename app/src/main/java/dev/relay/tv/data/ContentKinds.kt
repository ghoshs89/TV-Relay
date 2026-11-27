package dev.relay.tv.data

/**
 * Content types for filtering Live TV channels and Movies / Series lists without digging through
 * hundreds of provider categories. Computed once off the screen thread when a library is indexed.
 */
object ContentKinds {
    const val SPORTS = "sports"
    const val NEWS = "news"
    const val MOVIES = "movies"
    const val KIDS = "kids"
    const val DOCS = "docs"
    const val MUSIC = "music"
    const val ENTERTAINMENT = "entertainment"

    const val ACTION = "action"
    const val COMEDY = "comedy"
    const val DRAMA = "drama"
    const val THRILLER = "thriller"
    const val SCIFI = "scifi"
    const val HORROR = "horror"

    /** Order in which Live TV content-type filter chips appear. */
    val LIVE_ORDER = listOf(SPORTS, NEWS, MOVIES, KIDS, DOCS, MUSIC, ENTERTAINMENT)

    /** Order in which Movies and Series content-type filter chips appear. */
    val TITLE_ORDER = listOf(ACTION, COMEDY, DRAMA, THRILLER, SCIFI, DOCS, KIDS, SPORTS, HORROR)

    fun label(kind: String): String = when (kind) {
        SPORTS -> "Sports"
        NEWS -> "News"
        MOVIES -> "Movies"
        KIDS -> "Kids"
        DOCS -> "Documentary"
        MUSIC -> "Music"
        ENTERTAINMENT -> "Entertainment"
        ACTION -> "Action"
        COMEDY -> "Comedy"
        DRAMA -> "Drama"
        THRILLER -> "Thriller"
        SCIFI -> "Sci-Fi"
        HORROR -> "Horror"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    // Order matters: specific topics beat broad ones ("Sky Sports News" -> Sports or News? News word in a sports channel vs Sports;
    // checking SPORTS before MOVIES so "Sony Sports Ten" beats generic brand words, and NEWS before ENTERTAINMENT).
    private val LIVE_CATEGORY_WORDS: List<Pair<String, List<String>>> = listOf(
        SPORTS to listOf(
            "SPORT", "SPORTS", "CRICKET", "FOOTBALL", "SOCCER", "NFL", "NBA", "NHL", "MLB", "UFC", "WWE",
            "MMA", "BOXING", "TENNIS", "GOLF", "F1", "FORMULA 1", "MOTORSPORT", "RACING", "RUGBY", "PPV",
            "IPL", "EPL", "PREMIER LEAGUE", "CHAMPIONS LEAGUE", "LA LIGA", "SERIE A", "BUNDESLIGA", "DAZN",
            "BEIN", "SUPERSPORT", "ESPN", "SKY SPORTS", "TNT SPORTS", "STAR SPORTS",
        ),
        NEWS to listOf(
            "NEWS", "SAMACHAR", "KHABAR", "KHOBOR", "BUSINESS", "FINANCE", "WEATHER", "PARLIAMENT", "CNBC",
            "BLOOMBERG",
        ),
        KIDS to listOf("KIDS", "CHILDREN", "CARTOON", "CARTOONS", "JUNIOR", "TOONS", "ANIME", "FAMILY"),
        DOCS to listOf(
            "DOCUMENTARY", "DOCUMENTARIES", "DOCU", "SCIENCE", "NATURE", "HISTORY", "WILDLIFE", "KNOWLEDGE",
            "DISCOVERY", "INFOTAINMENT", "EDUCATION",
        ),
        MUSIC to listOf("MUSIC", "MUSICAL", "SANGEET", "RADIO", "CONCERT", "CONCERTS", "HITS", "GAAN"),
        MOVIES to listOf("MOVIE", "MOVIES", "CINEMA", "CINEMAS", "FILM", "FILMS", "BOLLYWOOD", "HOLLYWOOD", "BOX OFFICE"),
        ENTERTAINMENT to listOf("ENTERTAINMENT", "GENERAL", "LIFESTYLE", "REALITY", "COMEDY", "DRAMA", "SERIAL"),
    )

    private val LIVE_CHANNEL_WORDS: List<Pair<String, List<String>>> = listOf(
        SPORTS to listOf(
            "SPORT", "SPORTS", "ESPN", "EUROSPORT", "SKY SPORTS", "TNT SPORTS", "PREMIER SPORTS", "STAR SPORTS",
            "SONY SPORTS", "SONY TEN", "TEN SPORTS", "DD SPORTS", "T SPORTS", "WILLOW", "CRICBUZZ", "ASTRO CRICKET",
            "FOX CRICKET", "SUPERSPORT", "BEIN", "DAZN", "FUBO", "NBA TV", "NFL NETWORK", "MLB NETWORK",
            "NHL NETWORK", "GOLF CHANNEL", "TENNIS CHANNEL", "MUTV", "LFCTV", "FIGHT", "UFC", "WWE", "RACING TV",
            "SKY F1", "MOTORVISION",
        ),
        NEWS to listOf(
            "NEWS", "SAMACHAR", "KHABAR", "BBC WORLD", "CNN", "CNBC", "BLOOMBERG", "AL JAZEERA", "FRANCE 24",
            "DW ENGLISH", "EURONEWS", "WION", "NDTV", "AAJ TAK", "INDIA TODAY", "TIMES NOW", "REPUBLIC",
            "ABP ANANDA", "ABP MAJHA", "ABP ASMITA", "ABP GANGA", "24 GHANTA", "ZEE 24", "KOLKATA TV",
            "SOMOY", "JAMUNA", "EKATTOR", "EKHON", "INDEPENDENT TV", "DBC", "CHANNEL 24", "ATN NEWS",
            "MSNBC", "C SPAN", "GB NEWS", "TALKTV", "TRT WORLD", "NHK WORLD", "CGTN", " CNA ",
        ),
        KIDS to listOf(
            "KIDS", "CARTOON", "NICKELODEON", "NICK JR", "NICKTOONS", "DISNEY", "POGO", "HUNGAMA", "SONIC",
            "SUPER HUNGAMA", "SONY YAY", "GUBBARE", "CHUTTI", "KOCHU", "CHINTU", "KUSHI", "DURONTO",
            "CBEEBIES", "CBBC", "BOOMERANG", "BABYTV", "BABY TV", "TINY POP", "POP MAX", "PBS KIDS", "TOONAMI",
        ),
        DOCS to listOf(
            "DISCOVERY", "NAT GEO", "NATIONAL GEOGRAPHIC", "ANIMAL PLANET", "HISTORY TV", "HISTORY CHANNEL",
            "HISTORY HD", "BBC EARTH", "SMITHSONIAN", "CURIOSITY", "LOVE NATURE", "WILD EARTH", "DOCUBAY",
            "CRIME INVESTIGATION", "INVESTIGATION DISCOVERY", "SCIENCE CHANNEL", "TRAVEL XP", "TRAVEL CHANNEL",
        ),
        MUSIC to listOf(
            "MUSIC", "SANGEET", "MTV", "VH1", "9XM", "9X JALWA", "9X TASHAN", "MASTIII", "ZING", "ZOOM",
            "B4U MUSIC", "SONY MIX", "SHOWBOX", "GAAN", "TRACE", "CLUBLAND", "KERRANG", "KISS TV", "MAGIC TV",
            "STINGRAY", "MEZZO",
        ),
        MOVIES to listOf(
            "MOVIES", "MOVIE", "CINEMA", "CINEPLEX", "FILMS", "FILM4", "STAR GOLD", "SONY MAX", "SONY WAH",
            "SONY PIX", "SONY AATH", "ZEE CINEMA", "ZEE ACTION", "ZEE BOLLYWOOD", "ZEE CLASSIC", "ZEE ANMOL CINEMA",
            "ZEE BANGLA CINEMA", "JALSHA MOVIES", "COLORS CINEPLEX", "&PICTURES", "AND PICTURES", "&FLIX",
            "AND FLIX", "&XPLORHD", "B4U MOVIES", "B4U KADAK", "GOLDMINES", "SHEMAROO ME", "HBO", "CINEMAX",
            "SHOWTIME", "STARZ", "TCM", "PARAMOUNT NETWORK", "AMC", "SKY CINEMA",
        ),
    )

    private val TITLE_CATEGORY_WORDS: List<Pair<String, List<String>>> = listOf(
        SPORTS to listOf("SPORT", "SPORTS", "WWE", "UFC", "WRESTLING", "FITNESS", "FOOTBALL", "CRICKET", "F1"),
        DOCS to listOf("DOCUMENTARY", "DOCUMENTARIES", "DOCU", "BIOGRAPHY", "NATURE", "HISTORY", "SCIENCE"),
        KIDS to listOf("KIDS", "CHILDREN", "FAMILY", "ANIMATION", "ANIMATED", "CARTOON", "ANIME", "DISNEY", "PIXAR"),
        HORROR to listOf("HORROR", "THRILLER HORROR", "ZOMBIE", "SLASHERS"),
        SCIFI to listOf("SCI FI", "SCIFI", "SCIENCE FICTION", "FANTASY", "MARVEL", "DC UNIVERSE"),
        THRILLER to listOf("THRILLER", "CRIME", "MYSTERY", "SUSPENSE", "DETECTIVE", "NOIR"),
        ACTION to listOf("ACTION", "ADVENTURE", "WAR", "WESTERN", "MARTIAL ARTS"),
        COMEDY to listOf("COMEDY", "COMEDIES", "STAND UP", "STANDUP", "SITCOM", "HUMOR", "HUMOUR"),
        DRAMA to listOf("DRAMA", "ROMANCE", "ROMANTIC", "MELODRAMA"),
    )

    private class CompiledRule(val kind: String, val singleWords: Set<String>, val phrases: List<String>)

    private fun compile(rules: List<Pair<String, List<String>>>): List<CompiledRule> =
        rules.map { (kind, rawKeys) ->
            val singles = HashSet<String>()
            val phrases = ArrayList<String>()
            for (raw in rawKeys) {
                val norm = normalizeWords(raw)
                if (norm.isEmpty()) continue
                if (' ' in norm) phrases.add(" $norm ") else singles.add(norm)
            }
            CompiledRule(kind, singles, phrases)
        }

    private val LIVE_CAT_RULES by lazy { compile(LIVE_CATEGORY_WORDS) }
    private val LIVE_CH_RULES by lazy { compile(LIVE_CHANNEL_WORDS) }
    private val TITLE_CAT_RULES by lazy { compile(TITLE_CATEGORY_WORDS) }

    /** Uppercase ASCII word string ("SKY SPORTS F1 HD") without regex allocations. */
    internal fun normalizeWords(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        var space = true
        for (c in raw) {
            val up = c.uppercaseChar()
            if (up in 'A'..'Z' || up in '0'..'9' || up == '&') {
                sb.append(up)
                space = false
            } else if (!space) {
                sb.append(' ')
                space = true
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun match(raw: String, rules: List<CompiledRule>): String? {
        val cleaned = if (raw.any { it.code > 126 }) ProviderNames.clean(raw) else raw
        val norm = normalizeWords(cleaned)
        if (norm.isEmpty()) return null
        val padded = " $norm "
        // Split words without regex.
        val words = HashSet<String>(8)
        var start = 0
        for (i in 0..norm.length) {
            if (i == norm.length || norm[i] == ' ') {
                if (i > start) words.add(norm.substring(start, i))
                start = i + 1
            }
        }
        for (rule in rules) {
            for (w in words) if (w in rule.singleWords) return rule.kind
            for (p in rule.phrases) if (padded.contains(p)) return rule.kind
        }
        return null
    }

    /** Content type of a Live TV provider category name, or null when general/regional only. */
    fun ofLiveCategory(name: String): String? = match(name, LIVE_CAT_RULES)

    /**
     * Content type of a Live TV channel. Specific channel-name cues ("Star Sports 1", "ABP Ananda")
     * win over broad regional/entertainment categories ("INDIA | BENGALI", "UK | General").
     */
    fun ofChannel(name: String, categoryKind: String?): String? {
        val specific = match(name, LIVE_CH_RULES)
        if (specific != null) return specific
        return categoryKind
    }

    /** Content type of a Movies or Series provider category name, or null when broad ("New Releases", "4K", "Hindi"). */
    fun ofTitleCategory(name: String): String? = match(name, TITLE_CAT_RULES)

    fun liveCategoryKinds(cats: List<Category>): Map<String, String> = buildMap {
        for (c in cats) if (!c.adult) ofLiveCategory(c.name)?.let { put(c.id, it) }
    }

    fun titleCategoryKinds(cats: List<Category>): Map<String, String> = buildMap {
        for (c in cats) if (!c.adult) ofTitleCategory(c.name)?.let { put(c.id, it) }
    }
}
