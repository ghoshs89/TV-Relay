package dev.relay.tv.data

/**
 * Which installed app a trailer opens in: SmartTube first, then Morphe, then ReVanced (or RVX), then
 * YouTube itself (the TV build before the phone build). Browsers and anything else that happens to
 * open YouTube links are never chosen; with none of these, the caller shows the QR code instead.
 */
object TrailerApps {
    enum class Family(val label: String) { SMARTTUBE("SmartTube"), MORPHE("Morphe"), REVANCED("ReVanced"), YOUTUBE("YouTube") }

    /** Known package names, in order of preference within the whole list. */
    val KNOWN: List<String> = listOf(
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta",
        "com.liskovsoft.smarttubetv",
        "app.morphe.android.youtube",
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.youtube.googletv",
        "com.google.android.youtube",
    )

    /** The family of [pkg], or null for an app that is not a YouTube client Relay trusts. */
    fun familyOf(pkg: String): Family? {
        val p = pkg.lowercase()
        return when {
            p == "com.teamsmart.videomanager.tv" || p.startsWith("com.liskovsoft.smarttube") -> Family.SMARTTUBE
            p.startsWith("app.morphe.") -> Family.MORPHE
            p.startsWith("app.revanced.") || p.startsWith("app.rvx.") -> Family.REVANCED
            p == "com.google.android.youtube" || p.startsWith("com.google.android.youtube.") &&
                !p.contains("kids") && !p.contains("music") && !p.contains("creator") -> Family.YOUTUBE
            else -> null
        }
    }

    /**
     * The package to open a trailer with, from the packages that can open the link (or are installed),
     * or null when none is a trusted YouTube client. Ties within a family follow [KNOWN], then the name.
     */
    fun pick(candidates: Collection<String>): String? =
        candidates.distinct()
            .mapNotNull { pkg -> familyOf(pkg)?.let { pkg to it } }
            .sortedWith(
                compareBy<Pair<String, Family>> { it.second.ordinal }
                    .thenBy { KNOWN.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.first },
            )
            .firstOrNull()?.first
}
