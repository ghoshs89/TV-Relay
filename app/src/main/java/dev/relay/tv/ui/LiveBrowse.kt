package dev.relay.tv.ui

import dev.relay.tv.data.Category
import dev.relay.tv.data.Channel
import dev.relay.tv.data.ChannelKeys
import dev.relay.tv.data.LiveFilters

/** Provider categories grouped under a country or language, e.g. "CA | News" -> Canada / News. */
data class CatGroup(val key: String, val label: String, val cats: List<Category>)

object LiveBrowse {
    // "CA | News", "|IN| Hindi", "[UK] Sports", "USA: Locals", "INDIA - HINDI", "ASIA | INDIA - Sports", "EX-YU » Sport"
    private val PREFIX = Regex("^\\s*[\\[(|]?\\s*([A-Za-z0-9][A-Za-z0-9 \\-]{1,16})\\s*(?:[\\])|]\\s*[:|\\-–»•✦]?|[:|\\-–»•✦])\\s*(.+)$")

    private val NAMES = mapOf(
        // Countries (ISO codes, plus the variants providers use)
        "AD" to "Andorra", "AE" to "UAE", "UAE" to "UAE", "AF" to "Afghanistan", "AL" to "Albania", "AM" to "Armenia",
        "AO" to "Angola", "AR" to "Argentina", "AT" to "Austria", "AU" to "Australia", "AUS" to "Australia",
        "AZ" to "Azerbaijan", "BA" to "Bosnia", "BD" to "Bangladesh", "BE" to "Belgium", "BG" to "Bulgaria",
        "BH" to "Bahrain", "BO" to "Bolivia", "BR" to "Brazil", "BY" to "Belarus", "CA" to "Canada", "CAN" to "Canada",
        "CH" to "Switzerland", "CL" to "Chile", "CN" to "China", "CO" to "Colombia", "CR" to "Costa Rica", "CU" to "Cuba",
        "CY" to "Cyprus", "CZ" to "Czechia", "DE" to "Germany", "GER" to "Germany", "DK" to "Denmark", "DO" to "Dominican Republic",
        "DZ" to "Algeria", "EC" to "Ecuador", "EE" to "Estonia", "EG" to "Egypt", "ES" to "Spain", "ESP" to "Spain",
        "ET" to "Ethiopia", "FI" to "Finland", "FR" to "France", "FRA" to "France", "GB" to "UK", "UK" to "UK",
        "GE" to "Georgia", "GH" to "Ghana", "GR" to "Greece", "GT" to "Guatemala", "HK" to "Hong Kong", "HN" to "Honduras",
        "HR" to "Croatia", "HU" to "Hungary", "ID" to "Indonesia", "IE" to "Ireland", "IL" to "Israel", "IN" to "India",
        "IND" to "India", "IQ" to "Iraq", "IR" to "Iran", "IS" to "Iceland", "IT" to "Italy", "ITA" to "Italy",
        "JM" to "Jamaica", "JO" to "Jordan", "JP" to "Japan", "KE" to "Kenya", "KR" to "Korea", "KW" to "Kuwait",
        "KZ" to "Kazakhstan", "LB" to "Lebanon", "LK" to "Sri Lanka", "LT" to "Lithuania", "LU" to "Luxembourg",
        "LV" to "Latvia", "LY" to "Libya", "MA" to "Morocco", "MD" to "Moldova", "ME" to "Montenegro", "MK" to "North Macedonia",
        "MT" to "Malta", "MX" to "Mexico", "MY" to "Malaysia", "NG" to "Nigeria", "NL" to "Netherlands", "NO" to "Norway",
        "NP" to "Nepal", "NZ" to "New Zealand", "OM" to "Oman", "PA" to "Panama", "PE" to "Peru", "PH" to "Philippines",
        "PK" to "Pakistan", "PL" to "Poland", "PR" to "Puerto Rico", "PT" to "Portugal", "PY" to "Paraguay", "QA" to "Qatar",
        "RO" to "Romania", "RS" to "Serbia", "RU" to "Russia", "SA" to "Saudi Arabia", "SE" to "Sweden", "SG" to "Singapore",
        "SI" to "Slovenia", "SK" to "Slovakia", "SN" to "Senegal", "SO" to "Somalia", "SV" to "El Salvador", "SY" to "Syria",
        "TH" to "Thailand", "TN" to "Tunisia", "TR" to "Turkey", "TUR" to "Turkey", "TW" to "Taiwan", "TZ" to "Tanzania",
        "UA" to "Ukraine", "UG" to "Uganda", "US" to "USA", "USA" to "USA", "UY" to "Uruguay", "VE" to "Venezuela",
        "VN" to "Vietnam", "YE" to "Yemen", "ZA" to "South Africa", "ZM" to "Zambia", "ZW" to "Zimbabwe",
        // Regions and languages
        "ASIA" to "Asia", "INTL" to "International", "INTERNATIONAL" to "International",
        "EUROPE" to "Europe", "EU" to "Europe", "MIDDLE EAST" to "Middle East",
        "NORTH AMERICA" to "North America", "SOUTH ASIA" to "South Asia",
        "AR-AR" to "Arabic", "ARA" to "Arabic", "ARAB" to "Arabic", "AFR" to "Africa", "LAT" to "Latino",
        "LATAM" to "Latin America", "LATINO" to "Latino", "CAR" to "Caribbean", "CARIB" to "Caribbean", "EN" to "English",
        "ENG" to "English", "EX-YU" to "Ex-Yugoslavia", "EXYU" to "Ex-Yugoslavia", "YU" to "Ex-Yugoslavia",
        "KUR" to "Kurdish", "PERSIAN" to "Persian", "TAMIL" to "Tamil", "PUNJABI" to "Punjabi", "URDU" to "Urdu",
        "BANGLA" to "Bangla", "HINDI" to "Hindi", "BENGALI" to "Bengali", "TELUGU" to "Telugu", "MALAYALAM" to "Malayalam",
        "KANNADA" to "Kannada", "MARATHI" to "Marathi", "GUJARATI" to "Gujarati", "INDIA" to "India", "CANADA" to "Canada",
        "PAKISTAN" to "Pakistan", "BANGLADESH" to "Bangladesh", "ARABIC" to "Arabic", "AFRICA" to "Africa",
        "CARIBBEAN" to "Caribbean", "ENGLISH" to "English", "FRANCE" to "France", "GERMANY" to "Germany", "SPAIN" to "Spain",
        "ITALY" to "Italy", "PORTUGAL" to "Portugal", "BRAZIL" to "Brazil", "MEXICO" to "Mexico", "TURKEY" to "Turkey",
        "GREECE" to "Greece", "POLAND" to "Poland",
        // Topics some providers group by
        "SPORT" to "Sports", "SPORTS" to "Sports", "PPV" to "Pay-per-view", "VIP" to "VIP", "4K" to "4K", "UHD" to "4K",
        "KIDS" to "Kids", "MUSIC" to "Music", "NEWS" to "News",
    )

    private val KNOWN = setOf(
        "INDIA", "CANADA", "PAKISTAN", "BANGLA", "BANGLADESH", "ARABIC", "LATINO", "AFRICA", "CARIBBEAN",
        "ENGLISH", "HINDI", "PUNJABI", "TAMIL", "TELUGU", "BENGALI", "MALAYALAM", "KANNADA", "MARATHI",
        "GUJARATI", "URDU", "FRANCE", "GERMANY", "SPAIN", "ITALY", "PORTUGAL", "BRAZIL", "MEXICO", "TURKEY",
        "GREECE", "POLAND", "EX-YU", "EXYU", "LATAM", "SPORTS", "SPORT", "KIDS", "MUSIC", "NEWS",
        "ASIA", "INTL", "INTERNATIONAL", "EUROPE", "EU", "MIDDLE EAST", "NORTH AMERICA", "SOUTH ASIA",
    )

    // Synonym keys merge into one canonical folder key so providers mixing "IN |" and "INDIA -" stay grouped.
    private val CANONICAL = mapOf(
        "IND" to "IN", "INDIA" to "IN",
        "USA" to "US",
        "GB" to "UK",
        "CAN" to "CA", "CANADA" to "CA",
        "AUS" to "AU",
        "GER" to "DE", "GERMANY" to "DE",
        "FRA" to "FR", "FRANCE" to "FR",
        "ESP" to "ES", "SPAIN" to "ES",
        "ITA" to "IT", "ITALY" to "IT",
        "TUR" to "TR", "TURKEY" to "TR",
        "UAE" to "AE",
        "ARA" to "AR-AR", "ARAB" to "AR-AR", "ARABIC" to "AR-AR",
        "EXYU" to "EX-YU", "YU" to "EX-YU",
        "ENG" to "EN", "ENGLISH" to "EN",
        "BANGLADESH" to "BD",
        "PAKISTAN" to "PK",
        "PORTUGAL" to "PT", "BRAZIL" to "BR", "MEXICO" to "MX", "GREECE" to "GR", "POLAND" to "PL",
        "AFRICA" to "AFR", "CARIBBEAN" to "CAR", "CARIB" to "CAR", "LATINO" to "LAT", "SPORT" to "SPORTS",
        "UHD" to "4K", "INTERNATIONAL" to "INTL", "EUROPE" to "EU",
    )

    private val REGIONAL = setOf("ASIA", "EU", "INTL", "MIDDLE EAST", "NORTH AMERICA", "SOUTH ASIA", "AFR", "CAR", "LATAM")

    private fun splitOnce(name: String): Pair<String, String>? {
        val m = PREFIX.find(name) ?: return null
        val code = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty()) return null
        val up = code.uppercase()
        // Only codes we can name: "HR" is Croatia, but an unknown "TS" isn't treated as a country.
        if (up !in NAMES && up !in KNOWN) return null
        return (CANONICAL[up] ?: up) to rest
    }

    fun split(name: String): Pair<String, String>? {
        val first = splitOnce(name) ?: return null
        if (first.first in REGIONAL) {
            val inner = splitOnce(first.second)
            if (inner != null) return inner
        }
        return first
    }

    fun label(key: String): String =
        NAMES[key] ?: key.lowercase().split('-').joinToString("-") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Display name of a country or language code, or null when it isn't one Relay knows. */
    fun nameOf(code: String): String? = NAMES[code.uppercase()]

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

    const val F_CATCHUP = LiveFilters.CATCHUP
    const val F_HD = LiveFilters.HD
    const val F_GUIDE = LiveFilters.GUIDE

    fun sortKey(ch: Channel): String = ChannelKeys.sortKey(ch.name)
}
