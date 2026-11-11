package dev.relay.tv.player

import dev.relay.tv.data.Langs
import dev.relay.tv.data.TrackPref

/** The decisions behind audio and subtitle choices, kept apart from the player so they can be tested. */
object TrackPrefs {
    /** "en, hi" or "en hi" gives [en, hi]: lowercase codes, blanks dropped. */
    fun codes(raw: String): List<String> =
        raw.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /**
     * Audio languages in order of preference: the codes typed in Settings, or else the profile's
     * language order (English, Hindi, Bengali...) plus the older single-language setting.
     */
    fun audioOrder(
        typed: String,
        profileOrder: List<String>,
        fallback: String,
        trackCodes: (String) -> List<String> = Langs::trackCodes,
    ): List<String> {
        val own = codes(typed)
        if (own.isNotEmpty()) return own
        return (profileOrder.flatMap(trackCodes) + listOfNotNull(fallback.ifBlank { null })).distinct()
    }

    fun textOrder(raw: String): List<String> = codes(raw)

    /** [first] goes ahead of [rest], without repeats. */
    fun preferring(first: String?, rest: List<String>): List<String> = (listOfNotNull(first) + rest).distinct()

    /** Subtitle text size from the Settings choice (0 small, 1 normal, 2 large, 3 largest). */
    fun subtitleScale(size: Int): Float = when (size) {
        0 -> 0.8f
        2 -> 1.3f
        3 -> 1.6f
        else -> 1.0f
    }

    enum class Kind { Audio, Text }

    /** One track the player is playing right now, in the order the player lists them. */
    data class Selected(val kind: Kind, val language: String?)

    /**
     * What to remember for a series from what is playing: the first audio track that names a
     * language, and the first subtitle track ("und" when it names none). No subtitle track means off.
     */
    fun remembered(selected: List<Selected>): TrackPref {
        val audio = selected.firstNotNullOfOrNull { if (it.kind == Kind.Audio) it.language else null }
        val text = selected.firstOrNull { it.kind == Kind.Text }?.let { it.language ?: "und" }
        return TrackPref(audio = audio, text = text, textOff = text == null)
    }
}
