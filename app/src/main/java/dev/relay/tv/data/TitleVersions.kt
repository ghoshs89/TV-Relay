package dev.relay.tv.data

/**
 * A film or series as the recommendation rules see it: everything they need, and nothing from the
 * catalog's live state, so the rules can be tested without a device.
 */
data class TitleRef(
    val tv: Boolean,
    val id: Int,
    val name: String,
    val year: String?,
    val tmdbId: String?,
    val categoryId: String,
    val rating: Double,
    val added: Long,
    val lang: String? = null,
    /** Content type of its category (see [ContentKinds]), when known. */
    val kind: String? = null,
) {
    val key: String get() = (if (tv) "series:" else "movie:") + id

    companion object {
        fun of(m: Movie, lang: String?, kind: String?) =
            TitleRef(false, m.id, m.name, m.year, m.tmdbId, m.categoryId, m.rating, m.added, lang, kind)

        fun of(s: Series, lang: String?, kind: String?) =
            TitleRef(true, s.id, s.name, s.year, s.tmdbId, s.categoryId, s.rating, s.added, lang, kind)
    }
}

/**
 * Providers often list one film several times ("Dune (2021) 4K", "HI - Dune (2021) FHD"). These rules
 * treat such copies as one title: watching one copy counts for all, and a row shows one of them.
 * Only ever run over candidates and history, never the whole library.
 */
object TitleVersions {
    /** The same for every copy of a title: TMDB's id when the provider gives one, otherwise cleaned name and year. */
    fun groupKey(tv: Boolean, name: String, year: String?, tmdbId: String?): String {
        val kind = if (tv) "s" else "m"
        val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it.all(Char::isDigit) }
        if (id != null) return "tmdb:$kind:$id"
        val y = year?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: Regex("""\b(19|20)\d{2}\b""").find(name)?.value
        return "name:$kind:${normalize(Tmdb.cleanTitle(name))}:${y.orEmpty()}"
    }

    fun groupKey(t: TitleRef): String = groupKey(t.tv, t.name, t.year, t.tmdbId)

    /** Lower case, accents and punctuation gone, single spaces. */
    fun normalize(title: String): String =
        java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /**
     * One copy per title, in the order the first copy of each appears: the copy in the viewer's best
     * language, then the best picture quality, then the higher rating.
     */
    fun collapse(items: List<TitleRef>, langs: LangRank): List<TitleRef> {
        val groups = LinkedHashMap<String, MutableList<TitleRef>>()
        for (t in items) groups.getOrPut(groupKey(t)) { ArrayList(2) }.add(t)
        return groups.values.map { copies ->
            if (copies.size == 1) copies[0]
            else copies.minWith(
                compareBy<TitleRef> { langs.rank(it.lang) }
                    .thenByDescending { ChannelVersions.quality(it.name) }
                    .thenByDescending { it.rating },
            )
        }
    }
}
