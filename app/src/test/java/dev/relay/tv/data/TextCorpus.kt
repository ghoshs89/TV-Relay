package dev.relay.tv.data

import kotlin.random.Random

/**
 * Names for the text-rule tests and the local timing run: the examples already used in the unit
 * tests, a port of the generator in tools/fake-provider (same word lists and styles, fixed seed),
 * and fuzzed variants with the oddities real providers produce. Pure and repeatable.
 */
object TextCorpus {

    /** Every name already used by LanguagesTest, VersionsTest, ChannelKeysTest and NamesTest. */
    val seedNames: List<String> = listOf(
        "EN - Amanda Knox (2016)",
        "HI - Pathaan (2023)",
        "BN - Aparajito (2022)",
        "ES - Nocturno (2026) (US)",
        "Untagged Film (2020)",
        "SO-IN - Ghamasaan (2024)",
        "SOUTH INDIAN HINDI DUBBED",
        "TAMIL MOVIES",
        "IN: NTV TELUGU",
        "BANGLADESH | ENTERTAINMENT",
        "BD: BANGLA NATOK",
        "Channel i HD",
        "ATN BANGLA",
        "BD - Hawa (2022)",
        "IN: Star Jalsha HD",
        "Zee Bangla",
        "INDIA | BANGLA",
        "IN: Star Plus HD",
        "&TV HD",
        "HINDI MOVIES",
        "UK | ENTERTAINMENT",
        "UK: BBC One",
        "Some Channel",
        "NETFLIX SERIES (MULTI-SUB)",
        "XX",
        "MISC",
        "English: 3 (75%)",
        "XX: 4",
        "MISC: 10",
        "UK: Sky News",
        "UK: Sky News HD",
        "UK | Sky News FHD",
        "UK: SKY NEWS ᴴᴰ",
        "Star Jalsha HD",
        "Star Jalsha (Backup)",
        "Zee Bangla 1080p",
        "Zee Bangla HEVC",
        "Star Sports 1 HD",
        "Star Sports 2 HD",
        "UK: CNN",
        "US: CNN",
        "HD",
        "SD",
        "X 4K",
        "X FHD",
        "X HD",
        "X",
        "X SD",
        "X HD Backup",
        "Sony Max 1080p",
        "Full HD · HEVC",
        "UK: Sky News FHD HEVC",
        "Standard",
        "UK: Sky News SD",
        "UK: Sky News FHD",
        "UK: A",
        "UK: B",
        "A SD",
        "A FHD",
        "A HD",
        "A",
        "B",
        "CA",
        "CBC CALGARY",
        "CA: CBC CALGARY",
        "UK",
        "BBC One",
        "[UK] | BBC One",
        "IN",
        "Star Plus",
        "IN - Star Plus",
        "Star Plus HD",
        "CA:",
        "Zee TV",
        "cbc  calgary",
        "CA: CBC  CALGARY",
        "star plus hd",
        "★ Star Plus HD ★",
        "zee tv",
        "---",
        "Sky Sports 4K",
        "ESPN | FHD",
        "Movies hevc",
        "Shdow TV",
        "Nocturno",
        "ES",
        "2026",
        "US",
        "Ghamasaan",
        "SO-IN",
        "2024",
        "Amanda Knox",
        "EN",
        "The Ark",
        "Spider-Man: No Way Home",
        "MONSTER - The Ed Gein Story",
        "2019",
        "HI - Film (2018)",
        "2026 · ES · US",
        "OSN+ SERIES 4K",
        "OSN+ SERIES ⁴ᴷ ³⁸⁴⁰ᴾ",
        "4K: ELEVEN SPORTS 1 UHD",
        "4K: ELEVEN SPORTS 1 ᵁᴴᴰ ³⁸⁴⁰ᴾ",
        "NEWS",
        "★ NEWS ★",
        "UK | BBC One",
        "Netflix Series",
        "HR",
        "Sports",
        "HR | Sports",
        "Croatia",
        "TS | Something",
        "India",
    )

    private val COUNTRIES = listOf("US", "UK", "IN", "BD", "CA", "ES", "FR", "DE", "PK", "AU")
    private val LANGS = listOf("EN", "HI", "BN", "TA", "ES", "FR", "DE", "UR")
    private val TOPICS = listOf("News", "Sports", "Movies", "Kids", "Music", "Entertainment", "Documentary", "Regional")
    private val GENRES = listOf("Action", "Comedy", "Drama", "Thriller", "Family", "Romance", "Horror", "Sci-Fi", "Animation", "Crime")
    private val WORDS = (
        "Silent River Golden Harbor Midnight Garden Broken Compass Winter Light Hidden Valley Red Horizon " +
            "Paper Moon Electric Dreams Last Train Burning Sky Quiet Storm Iron Crown Lost Island Velvet Night " +
            "Crimson Tide Distant Shore Falling Stars Wild Road Secret Orchard Glass City Northern Wind"
        ).split(" ")
    private val QUALITY = listOf("", " HD", " FHD", " 4K", " HEVC")
    private val STYLES = listOf("%s: ", "%s| ", "%s - ")

    /** Real brand names that the keyword tables look for, so the matcher is exercised on hits as well as misses. */
    private val BRANDS = listOf(
        "Star Jalsha", "Zee Bangla", "Sun TV", "Star Plus", "Zee TV", "NTV", "RTV", "ATN Bangla", "Channel i", "Somoy",
        "Jamuna", "Gemini", "Asianet", "Colors Bangla", "Sony SAB", "Aaj Tak", "Republic Bharat", "Sky News", "BBC One",
        "CBC Calgary", "Star Sports 1", "Star Sports 2", "Tamil News", "Telugu Movies", "Malayalam Music", "Hindi Cinema",
        "English Drama", "Bangla Natok", "Bangladesh Today", "&TV", "AND TV", "R Plus", "News18 Bangla", "TV9 Bangla",
    )

    /** One channel name per entry, in the style of the fake provider (about a quarter repeat the previous name with another quality). */
    fun channels(count: Int, seed: Int = 7): List<String> {
        val rnd = Random(seed)
        val out = ArrayList<String>(count)
        var base: String? = null
        repeat(count) {
            val country = COUNTRIES[rnd.nextInt(COUNTRIES.size)]
            val name = if (base != null && rnd.nextDouble() < 0.25) {
                base!!
            } else {
                val brand = if (rnd.nextDouble() < 0.15) BRANDS[rnd.nextInt(BRANDS.size)] else "${WORDS[rnd.nextInt(WORDS.size)]} ${WORDS[rnd.nextInt(WORDS.size)]}"
                STYLES[rnd.nextInt(STYLES.size)].format(country) + brand + " " + (1 + rnd.nextInt(99))
            }
            base = name
            out.add(name + QUALITY[rnd.nextInt(QUALITY.size)])
        }
        return out
    }

    /** Category names as the fake provider writes them ("UK| News", "HI MOVIES Action"). */
    fun categories(): List<String> =
        COUNTRIES.flatMap { c -> TOPICS.map { "$c| $it" } } +
            LANGS.flatMap { l -> GENRES.flatMap { listOf("$l MOVIES $it", "$l SERIES $it") } } +
            listOf(
                "SOUTH INDIAN HINDI DUBBED", "BANGLADESH | ENTERTAINMENT", "TAMIL MOVIES", "HINDI MOVIES", "ENGLISH", "BENGALI",
                "[UK] Sports", "(US) News", "| IN | Music", "Kids", "24/7", "", "   ", "★ VIP ★",
            )

    /** Movie and series titles as the fake provider writes them ("HI - Silent River 12 (2019)"). */
    fun titles(count: Int, seed: Int = 11): List<String> {
        val rnd = Random(seed)
        return List(count) { n ->
            val lang = LANGS[rnd.nextInt(LANGS.size)]
            val year = 1985 + rnd.nextInt(42)
            "$lang - ${WORDS[rnd.nextInt(WORDS.size)]} ${WORDS[rnd.nextInt(WORDS.size)]} $n ($year)"
        }
    }

    private val PREFIXES = listOf(
        "", "", "", "[UK] ", "(US) ", "| IN | ", "|BD| ", " UK : ", "UK:", "US-", "IN - ", "CA| ", "★ ", "🔥 ", "🇬🇧 ", "✅ ", "▶ ",
        "ᴴᴰ ", "  ", "- ", ": ", "» ", "xx: ", "SO-IN - ", "EN - ", "HI | ", "BN: ",
    )
    private val SUFFIXES = listOf(
        "", "", "", " HD", " FHD", " UHD", " 4K", " SD", " HQ", " LQ", " HEVC", " H.265", " H264", " x265", " 50FPS", " 60 FPS",
        " 1080p", " 720P", " 2160p", " RAW", " BACKUP", " BKP", " ALT", " Alternate", " ᴿᴬᵂ", " ⁴ᴷ", " ᵁᴴᴰ", " ³⁸⁴⁰ᴾ", " ꜰʜᴅ",
        " ★", " ●", "  ", " :", " |", " -", "HD", " (HD)", " [FHD]", " + ", " 24/7", " ᴴᴰ ᴴᴱᵛᶜ",
    )
    private val CASES = listOf<(String) -> String>({ it }, { it }, { it.uppercase() }, { it.lowercase() }, { it.replaceFirstChar { c -> c.lowercase() } })
    private val ODD = listOf(
        "", " ", "   ", "-", ":", "|", "UK:", "UK: ", "HD", "4K", "ᴿᴬᵂ", "⁴ᴷ", "★", "🔥", "🇬🇧🇺🇸", "1080p", "BD:", "IN:", "A", "AB:",
        "বাংলা নিউজ", "हिन्दी सिनेमा", "تلفزيون", "Télé Française", "Ünsal TV", "Ελληνικά", "日本放送", "Ǆ TV", "ß News", "İstanbul TV", "ﬁlm channel",
        "A".repeat(300), "UK: " + "Long Name ".repeat(40) + "HD", "Tab\tSeparated\tName", "Line\nBreak HD", "Quote \"Name\" FHD", "Back\\slash 4K",
        "ᴸᴵᵛᴱ ᴺᴱᵂˢ", "Zee Bangla ᴴᴰ", "ATN Bangla 4K", "Star Jalsha FHD", "NTV TELUGU", "SUN TV HD", "Sports 24/7 - Cricket HD+",
    )

    /** Real-looking names changed in the ways providers change them: tags, brackets, case, quality words, symbols, odd text. */
    fun fuzzed(count: Int, seed: Int = 23): List<String> {
        val rnd = Random(seed)
        val bases = channels(2_000, seed = 5) + BRANDS + seedNames
        return List(count) {
            val base = bases[rnd.nextInt(bases.size)]
            if (rnd.nextInt(12) == 0) {
                ODD[rnd.nextInt(ODD.size)]
            } else {
                val body = CASES[rnd.nextInt(CASES.size)](base)
                PREFIXES[rnd.nextInt(PREFIXES.size)] + body + SUFFIXES[rnd.nextInt(SUFFIXES.size)]
            }
        }
    }

    /** Category languages every name is tried with: unknown, each group, and "other". */
    val categoryLangs: List<String?> = listOf(null, Langs.EN, Langs.HI, Langs.BN, Langs.SOUTH, Langs.BD, Langs.OTHER)

    /** The whole corpus for the golden test: exactly [total] names (fewer only when [total] is smaller than the fixed examples). */
    fun channelNames(total: Int): List<String> {
        val fixed = seedNames + ODD + BRANDS
        if (total <= fixed.size) return fixed.take(total)
        val rest = total - fixed.size
        return fixed + channels(rest * 2 / 3, seed = 7) + fuzzed(rest - rest * 2 / 3, seed = 23)
    }
}
