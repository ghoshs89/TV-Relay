package dev.relay.tv.data

/**
 * Kids profile age eligibility and age-band classification (`0–6`, `7–9`, `10–12`).
 *
 * Works both offline (using provider category & title cues) and API-enriched (using TMDB/MDBList
 * certifications, genres, and adult flags). Mature ratings (`PG-13`, `TV-14`, `R`, `TV-MA`, `16+`,
 * `18+`, `A`) and mature genres (`Horror`, `Crime`, `Thriller`, `War`, `Adult`, unrated `Anime`) are
 * strictly blocked at every kids age tier.
 */
object KidsAge {
    const val AGE_6 = 6
    const val AGE_9 = 9
    const val AGE_12 = 12
    const val DEFAULT_MAX_AGE = AGE_12

    val TIERS = listOf(AGE_6, AGE_9, AGE_12)
    val BANDS = listOf("0-6", "7-9", "10-12")

    fun normalizeMaxAge(maxAge: Int): Int = when {
        maxAge <= 0 -> DEFAULT_MAX_AGE
        maxAge <= AGE_6 -> AGE_6
        maxAge <= AGE_9 -> AGE_9
        else -> AGE_12
    }

    fun label(age: Int): String = when (normalizeMaxAge(age)) {
        AGE_6 -> "Little kids (0–6)"
        AGE_9 -> "Older kids (7–9)"
        else -> "Pre-teens (10–12)"
    }

    fun bandOf(age: Int): String = when {
        age <= AGE_6 -> "0-6"
        age <= AGE_9 -> "7-9"
        else -> "10-12"
    }

    fun bandLabel(band: String): String = when (band) {
        "0-6" -> "For little kids (0–6)"
        "7-9" -> "For older kids (7–9)"
        "10-12" -> "For pre-teens (10–12)"
        else -> band
    }

    fun eligibleBands(maxAge: Int): List<String> = when (normalizeMaxAge(maxAge)) {
        AGE_6 -> listOf("0-6")
        AGE_9 -> listOf("0-6", "7-9")
        else -> BANDS
    }

    private val PRESCHOOL_WORDS = listOf(
        "PRESCHOOL", "PRE SCHOOL", "TODDLER", "TODDLERS", "NURSERY", "NICK JR", "NICKELODEON JR",
        "DISNEY JR", "DISNEY JUNIOR", "COCOMELON", "PEPPA", "BLUEY", "PAW PATROL", "SESAME",
        "TELETUBBIES", "POCOYO", "JUNIOR", "JR", "BABY",
    )

    private val KIDS_CAT_WORDS = listOf(
        "KIDS", "KID", "CHILDREN", "CHILD", "CARTOON", "CARTOONS", "TOON", "TOONS",
        "NICKELODEON", "POGO", "HUNGAMA", "DISNEY", "PIXAR",
    )

    private val FAMILY_ANIMATION_WORDS = listOf(
        "FAMILY", "ANIMATION", "ANIMATED",
    )

    private val BLOCKED_WORDS = listOf(
        "HORROR", "CRIME", "TRUE CRIME", "THRILLER", "WAR", "ADULT", "XXX", "18+",
        "EROTIC", "ZOMBIE", "SLASHER", "SLASHERS", "NOIR", "GORE",
    )

    private val BLOCKED_TMDB_GENRES = setOf(27, 80, 53, 10752, 10768)
    private val SAFE_TMDB_GENRES = setOf(16, 10751, 10762)
    private val SAFE_PG_GENRE_NAMES = setOf(
        "animation", "family", "kids", "adventure", "fantasy", "comedy", "music", "musical",
        "science fiction", "sci-fi", "sci-fi & fantasy", "action & adventure",
    )

    /** Minimum suitable age implied by a certification string, or null when unrated/unknown. */
    fun minAgeForCert(cert: String?): Int? {
        val raw = cert?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val c = raw.replace("US:", "").replace("U.S.:", "").trim()
        val compact = c.replace("-", "").replace(" ", "")
        return when (compact) {
            "G", "TVY", "TVG", "U", "AL", "ALL", "0", "0+", "3", "3+", "4+", "6", "6+", "E", "C", "C8" -> AGE_6
            "TVY7", "TVY7FV", "Y7", "7", "7+", "UA7+", "U/A7+", "PG7" -> AGE_9
            "PG", "TVPG", "10", "10+", "12", "12A", "PG12", "UA", "U/A", "PG10" -> AGE_12
            "PG13", "13", "13+", "UA13+", "U/A13+", "M" -> 13
            "TV14", "14", "14+", "14A" -> 14
            "15", "15+", "MA15+" -> 15
            "16", "16+", "UA16+", "U/A16+" -> 16
            "R", "TVMA", "NC17", "A", "18", "18+", "X", "XX", "XXX", "MA", "R18", "AO" -> 18
            else -> when {
                compact in setOf("NR", "UNRATED", "NOTRATED", "N/A", "NONE") -> null
                Regex("^(\\d{1,2})\\+?$").matchEntire(compact)?.let { m -> m.groupValues[1].toIntOrNull() } != null ->
                    Regex("^(\\d{1,2})\\+?$").matchEntire(compact)!!.groupValues[1].toInt()
                else -> null
            }
        }
    }

    private fun wordStr(s: String): String = " " + ContentKinds.normalizeWords(s) + " "

    private fun hasAnyWord(padded: String, words: List<String>): Boolean =
        words.any { w -> padded.contains(" $w ") }

    private fun isBlocked(
        titleWords: String,
        catWords: String,
        genres: List<String>,
        genreIds: List<Int>,
    ): Boolean {
        if (genreIds.any { it in BLOCKED_TMDB_GENRES }) return true
        if (hasAnyWord(titleWords, BLOCKED_WORDS) || hasAnyWord(catWords, BLOCKED_WORDS)) return true
        if (genres.any { g -> hasAnyWord(wordStr(g), BLOCKED_WORDS) }) return true
        return false
    }

    /**
     * Classifies a title into its youngest appropriate kids tier (`6` = 0–6, `9` = 7–9, `12` = 10–12),
     * or returns null if the title is NOT verified safe for kids profiles.
     */
    fun classifyAgeBand(
        title: String,
        categoryName: String,
        certification: String? = null,
        genres: List<String> = emptyList(),
        genreIds: List<Int> = emptyList(),
        adult: Boolean = false,
    ): Int? {
        if (adult) return null
        val tw = wordStr(title)
        val cw = wordStr(categoryName)
        if (isBlocked(tw, cw, genres, genreIds)) return null

        val certAge = minAgeForCert(certification)
        if (certAge != null && certAge > AGE_12) return null

        val hasKidsOrFamilyGenre = genreIds.any { it in SAFE_TMDB_GENRES } ||
            genres.any { it.trim().lowercase() in setOf("animation", "family", "kids") }
        val explicitKidsCat = hasAnyWord(cw, PRESCHOOL_WORDS) || hasAnyWord(cw, KIDS_CAT_WORDS)
        val explicitFamilyCat = hasAnyWord(cw, FAMILY_ANIMATION_WORDS)
        val isAnime = tw.contains(" ANIME ") || cw.contains(" ANIME ") ||
            genres.any { it.equals("anime", ignoreCase = true) }

        // Generic "ANIME" categories often contain mature 14+/18+ shows: require a kids category or safe <=12 cert.
        if (isAnime && certAge == null && !explicitKidsCat && !cw.contains(" FAMILY ")) {
            return null
        }

        val preschoolCue = hasAnyWord(tw, PRESCHOOL_WORDS) || hasAnyWord(cw, PRESCHOOL_WORDS)

        if (certAge != null) {
            return when {
                certAge <= AGE_6 -> AGE_6
                certAge <= AGE_9 -> if (preschoolCue) AGE_6 else AGE_9
                else -> {
                    // PG / TV-PG: allow when genres fit kids/pre-teen or genres are not yet loaded.
                    val lowerGenres = genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val pgOk = explicitKidsCat || explicitFamilyCat || hasKidsOrFamilyGenre ||
                        lowerGenres.isEmpty() || lowerGenres.any { it in SAFE_PG_GENRE_NAMES }
                    if (pgOk) AGE_12 else null
                }
            }
        }

        // Unrated (offline or before API cert arrives): require explicit kids/family/animation signals.
        return when {
            preschoolCue -> AGE_6
            explicitKidsCat || genreIds.contains(10762) || genres.any { it.equals("kids", ignoreCase = true) } -> AGE_9
            explicitFamilyCat || hasKidsOrFamilyGenre -> AGE_12
            else -> null
        }
    }

    /** True when the title's age band is present and fits within [maxAge]. */
    fun isKidsEligible(
        title: String,
        categoryName: String,
        maxAge: Int = DEFAULT_MAX_AGE,
        certification: String? = null,
        genres: List<String> = emptyList(),
        genreIds: List<Int> = emptyList(),
        adult: Boolean = false,
    ): Boolean {
        val band = classifyAgeBand(title, categoryName, certification, genres, genreIds, adult) ?: return false
        return band <= normalizeMaxAge(maxAge)
    }

    /** Convenience check on a TMDB recommendation using its `adult` flag and `genre_ids`. */
    fun isTmdbRecAllowedForKids(rec: Tmdb.Rec): Boolean =
        !rec.adult && rec.genreIds.none { it in BLOCKED_TMDB_GENRES }
}
