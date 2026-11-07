package dev.relay.tv.data

/**
 * Display cleanup for the names providers send. Raw names are kept everywhere for search and
 * matching; these functions only change what's shown.
 */
object ProviderNames {
    // Superscript and small-capital letters providers use for "⁴ᴷ", "ᵁᴴᴰ", "³⁸⁴⁰ᴾ".
    private val SUPER = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J', 'ᴷ' to 'K',
        'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V',
        'ᵂ' to 'W', 'ˣ' to 'X', 'ʸ' to 'Y', 'ᶻ' to 'Z', 'ᵃ' to 'A', 'ᵇ' to 'B', 'ᶜ' to 'C', 'ᵈ' to 'D', 'ᵉ' to 'E',
        'ᶠ' to 'F', 'ᵍ' to 'G', 'ʰ' to 'H', 'ⁱ' to 'I', 'ʲ' to 'J', 'ᵏ' to 'K', 'ˡ' to 'L', 'ᵐ' to 'M', 'ⁿ' to 'N',
        'ᵒ' to 'O', 'ᵖ' to 'P', 'ʳ' to 'R', 'ˢ' to 'S', 'ᵗ' to 'T', 'ᵘ' to 'U', 'ᵛ' to 'V', 'ʷ' to 'W', '⁺' to '+',
        'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E', 'ʜ' to 'H', 'ᴋ' to 'K', 'ᴍ' to 'M', 'ɴ' to 'N', 'ᴏ' to 'O', 'ᴘ' to 'P',
        'ʀ' to 'R', 'ᴛ' to 'T', 'ᴜ' to 'U', 'ᴠ' to 'V', 'ᴡ' to 'W',
    )

    // Decorative symbols with no meaning in a name.
    private val DECOR = Regex("[★☆✦✧✪✯✰⭐◉●○◆◇■□▶►◀◄♦♥♠♣✔✓✅❤️⚽🏆🔥🎬📺🇦-🇿]+")
    private val RESOLUTION = Regex("(?i)\\b\\d{3,4}p\\b")
    private val SPACES = Regex("\\s{2,}")
    private val EDGE_PUNCT = Regex("^[\\s:|\\-–»]+|[\\s:|\\-–»]+$")

    private const val EDGE = " :|-–»"

    /**
     * Plain ASCII names with nothing to tidy skip the regular expressions; most names in a large
     * playlist are like that, and this runs for every one of them.
     */
    private fun alreadyClean(name: String): Boolean {
        if (name.isEmpty() || name[0] in EDGE || name[name.length - 1] in EDGE) return false
        var prev = ' '
        for (c in name) {
            if (c.code > 126) return false
            if (c == ' ' && prev == ' ') return false
            if ((c == 'p' || c == 'P') && prev.isDigit()) return false
            prev = c
        }
        return true
    }

    /** "OSN+ SERIES ⁴ᴷ ³⁸⁴⁰ᴾ" -> "OSN+ SERIES 4K"; "★ NEWS ★" -> "NEWS". */
    fun clean(name: String): String {
        if (alreadyClean(name)) return name
        val mapped = buildString(name.length) {
            var prevSuper = false
            for (c in name) {
                val s = SUPER[c]
                if (s != null) {
                    // A run of superscripts becomes its own word: "SERIES⁴ᴷ" -> "SERIES 4K".
                    if (!prevSuper && isNotEmpty() && last() != ' ') append(' ')
                    append(s)
                    prevSuper = true
                } else {
                    if (prevSuper && c != ' ') append(' ')
                    append(c)
                    prevSuper = false
                }
            }
        }
        val out = mapped.replace(DECOR, " ").replace(RESOLUTION, " ").replace(SPACES, " ").replace(EDGE_PUNCT, "")
        return out.ifBlank { name.trim() }
    }
}

/**
 * A movie or series title split into what's worth showing: "ES - Nocturno (2026) (US)" becomes
 * title "Nocturno", language tag "ES", year 2026 and region "US".
 */
data class TitleParts(
    val title: String,
    val tag: String? = null,
    val year: String? = null,
    val region: String? = null,
) {
    /** "2026 · ES · US" style line, with anything known about the title. */
    fun meta(extra: List<String> = emptyList()): String = (listOfNotNull(year, tag, region) + extra).joinToString(" · ")

    companion object {
        // "ES - ", "SO-IN - ", "EN| ", "[HI] ", "4K-NF - "
        private val LEAD = Regex("^\\s*[\\[(]?\\s*([A-Z0-9][A-Z0-9+]{0,4}(?:-[A-Z0-9+]{1,4})?)\\s*[\\])]?\\s*(?:-|\\||:|–)\\s+(.+)$")
        private val LEAD_BRACKET = Regex("^\\s*[\\[(]([A-Z0-9][A-Z0-9+\\-]{0,8})[\\])]\\s*(.+)$")
        private val TRAIL_YEAR = Regex("\\s*[(\\[]((?:19|20)\\d{2})[)\\]]\\s*$")
        private val TRAIL_REGION = Regex("\\s*[(\\[]([A-Z]{2,3})[)\\]]\\s*$")
        private val TRAIL_DASH_YEAR = Regex("\\s+-\\s+((?:19|20)\\d{2})\\s*$")

        /** Just the leading language tag ("HI", "SO-IN"), cheaply, for indexing whole playlists. */
        fun tagOf(raw: String): String? {
            val t = raw.trimStart()
            if (t.isEmpty()) return null
            val c = t[0]
            if (!(c in 'A'..'Z' || c in '0'..'9' || c == '[' || c == '(' || c.code > 126)) return null
            val s = if (t.take(12).any { it.code > 126 }) ProviderNames.clean(t) else t
            val m = LEAD.find(s) ?: LEAD_BRACKET.find(s) ?: return null
            val code = m.groupValues[1]
            return code.takeIf { it.length <= 8 && it.count { ch -> ch.isLetter() } <= 6 && m.groupValues[2].isNotBlank() }
        }

        fun parse(raw: String, knownYear: String? = null): TitleParts {
            var s = ProviderNames.clean(raw)
            var tag: String? = null
            (LEAD.find(s) ?: LEAD_BRACKET.find(s))?.let { m ->
                val code = m.groupValues[1]
                // Tags are short codes, not the first word of a title like "MONSTER - ..."
                if (code.length <= 8 && code.count { it.isLetter() } <= 6 && m.groupValues[2].isNotBlank()) {
                    tag = code
                    s = m.groupValues[2].trim()
                }
            }
            var year: String? = null
            var region: String? = null
            // Strip trailing "(US)" and "(2026)" in any order, a couple of times.
            repeat(3) {
                TRAIL_REGION.find(s)?.let { m ->
                    if (region == null) region = m.groupValues[1]
                    s = s.substring(0, m.range.first).trim()
                }
                TRAIL_YEAR.find(s)?.let { m ->
                    if (year == null) year = m.groupValues[1]
                    s = s.substring(0, m.range.first).trim()
                }
                TRAIL_DASH_YEAR.find(s)?.let { m ->
                    if (year == null) year = m.groupValues[1]
                    s = s.substring(0, m.range.first).trim()
                }
            }
            if (s.isBlank()) s = raw.trim()
            return TitleParts(s, tag, knownYear?.take(4)?.takeIf { it.length == 4 } ?: year, region)
        }
    }
}
