package dev.relay.tv.ui

import dev.relay.tv.RelayApp
import dev.relay.tv.data.Category
import dev.relay.tv.data.Channel

/** Provider categories grouped under a country or language, e.g. "CA | News" -> Canada / News. */
data class CatGroup(val key: String, val label: String, val cats: List<Category>)

object LiveBrowse {
    // "CA | News", "|IN| Hindi", "[UK] Sports", "USA: Locals", "INDIA - HINDI", "EX-YU » Sport"
    private val PREFIX = Regex("^\\s*[\\[(|]?\\s*([A-Za-z][A-Za-z\\-]{1,14})\\s*(?:[\\])|]\\s*[:|\\-–»•✦]?|[:|\\-–»•✦])\\s*(.+)$")

    private val NAMES = mapOf(
        "CA" to "Canada", "US" to "USA", "USA" to "USA", "UK" to "UK", "GB" to "UK", "IN" to "India",
        "BD" to "Bangladesh", "PK" to "Pakistan", "LK" to "Sri Lanka", "NP" to "Nepal", "AR" to "Arabic",
        "ARA" to "Arabic", "FR" to "France", "DE" to "Germany", "ES" to "Spain", "IT" to "Italy",
        "PT" to "Portugal", "BR" to "Brazil", "MX" to "Mexico", "AU" to "Australia", "NZ" to "New Zealand",
        "IE" to "Ireland", "NL" to "Netherlands", "BE" to "Belgium", "SE" to "Sweden", "NO" to "Norway",
        "DK" to "Denmark", "FI" to "Finland", "PL" to "Poland", "TR" to "Turkey", "GR" to "Greece",
        "RU" to "Russia", "UA" to "Ukraine", "RO" to "Romania", "HU" to "Hungary", "CZ" to "Czechia",
        "AL" to "Albania", "PH" to "Philippines", "CN" to "China", "JP" to "Japan", "KR" to "Korea",
        "VN" to "Vietnam", "TH" to "Thailand", "MY" to "Malaysia", "SG" to "Singapore", "ID" to "Indonesia",
        "IR" to "Iran", "IL" to "Israel", "EG" to "Egypt", "MA" to "Morocco", "NG" to "Nigeria",
        "ZA" to "South Africa", "KE" to "Kenya", "AF" to "Africa", "LAT" to "Latino", "LATAM" to "Latin America",
        "CAR" to "Caribbean", "EN" to "English", "EX-YU" to "Ex-Yugoslavia", "EXYU" to "Ex-Yugoslavia",
        "SPORT" to "Sports", "SPORTS" to "Sports", "PPV" to "Pay-per-view", "VIP" to "VIP", "4K" to "4K",
    )
    private val KNOWN = setOf(
        "INDIA", "CANADA", "PAKISTAN", "BANGLA", "BANGLADESH", "ARABIC", "LATINO", "AFRICA", "CARIBBEAN",
        "ENGLISH", "HINDI", "PUNJABI", "TAMIL", "TELUGU", "BENGALI", "MALAYALAM", "KANNADA", "MARATHI",
        "GUJARATI", "URDU", "FRANCE", "GERMANY", "SPAIN", "ITALY", "PORTUGAL", "BRAZIL", "MEXICO", "TURKEY",
        "GREECE", "POLAND", "EX-YU", "EXYU", "LATAM", "SPORTS", "SPORT", "KIDS", "MUSIC", "NEWS",
    )

    fun split(name: String): Pair<String, String>? {
        val m = PREFIX.find(name) ?: return null
        val code = m.groupValues[1]
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty()) return null
        val up = code.uppercase()
        val ok = (code.length in 2..4 && code == up) || up in KNOWN
        return if (ok) up to rest else null
    }

    fun label(key: String): String =
        NAMES[key] ?: key.lowercase().split('-').joinToString("-") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Null when the provider doesn't use prefixes consistently enough to be worth grouping. */
    fun group(cats: List<Category>): List<CatGroup>? {
        val parsed = cats.map { c -> c to split(c.name) }
        val counts = parsed.mapNotNull { it.second?.first }.groupingBy { it }.eachCount()
        val real = counts.filterValues { it >= 2 }.keys
        val covered = parsed.count { it.second?.first in real }
        if (real.size < 2 || covered * 2 < cats.size) return null
        val byKey = LinkedHashMap<String, MutableList<Category>>()
        val other = mutableListOf<Category>()
        for ((c, sp) in parsed) {
            if (sp != null && sp.first in real) {
                byKey.getOrPut(sp.first) { mutableListOf() } += c.copy(name = sp.second)
            } else {
                other += c
            }
        }
        val groups = byKey.map { (k, v) -> CatGroup(k, label(k), v) }.toMutableList()
        if (other.isNotEmpty()) groups += CatGroup("__other", "Other", other)
        return groups
    }

    // ---------- Sort and filters ----------

    val SORTS = listOf("Provider order", "Number", "A–Z", "Favourites first")

    const val F_CATCHUP = "catchup"
    const val F_HD = "hd"
    const val F_GUIDE = "guide"

    private val HD = Regex("(?i)\\b(HD|FHD|UHD|4K|HEVC|H265)\\b")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]+")

    fun sortKey(ch: Channel): String = splitChannelName(ch.name).second.lowercase().replace(NON_ALNUM, "").trim()

    fun apply(
        base: List<Channel>,
        sort: Int,
        filters: Set<String>,
        find: String,
        favIds: Set<Int>,
    ): List<Channel> {
        val guide = RelayApp.instance.catalog.guide
        var list = base.asSequence()
        if (F_CATCHUP in filters) list = list.filter { it.hasArchive }
        if (F_HD in filters) list = list.filter { HD.containsMatchIn(it.name) }
        if (F_GUIDE in filters) list = list.filter { !it.epgId.isNullOrBlank() || guide?.entries(it.id)?.isNotEmpty() == true }
        val q = find.trim().lowercase()
        if (q.isNotEmpty()) {
            val tokens = q.split(' ').filter { it.isNotEmpty() }
            list = list.filter { ch -> val n = ch.name.lowercase(); tokens.all { n.contains(it) } }
        }
        val out = list.toList()
        return when (sort) {
            1 -> out.sortedWith(compareBy<Channel>({ if (it.num > 0) 0 else 1 }, { it.num }, { sortKey(it) }))
            2 -> out.sortedBy { sortKey(it) }
            3 -> out.sortedBy { if (it.id in favIds) 0 else 1 }
            else -> out
        }
    }
}
