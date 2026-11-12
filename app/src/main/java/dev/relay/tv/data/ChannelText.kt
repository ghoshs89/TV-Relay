package dev.relay.tv.data

/**
 * Everything the channel lists work out from a provider's channel name. [versionKey] already has
 * the language appended ("uk:skynews|EN"), exactly as the catalog stores it.
 */
data class ChannelFacts(
    val lang: String?,
    val versionKey: String,
    val quality: Int,
    val sortKey: String,
    val hd: Boolean,
)

/**
 * All five facts for one channel in one pass. Cleaning a name is the costly part and used to run
 * three times per channel (language, version key, quality); here it runs once. Pure, so it is
 * unit tested against a frozen copy of the old rules (ChannelFactsGoldenTest).
 */
object ChannelText {

    /** [categoryLang] is the language of the channel's provider category, or null when unknown. */
    fun derive(name: String, categoryLang: String?): ChannelFacts {
        val clean = ProviderNames.clean(name)
        val lang = LanguageDetector.ofChannelCleaned(name, clean, categoryLang)
        return ChannelFacts(
            lang = lang,
            versionKey = ChannelVersions.keyCleaned(clean) + "|" + (lang ?: ""),
            quality = ChannelVersions.qualityOf(name, clean),
            sortKey = ChannelKeys.sortKey(name),
            hd = ChannelKeys.isHd(name),
        )
    }
}
