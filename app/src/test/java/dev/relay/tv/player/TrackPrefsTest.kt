package dev.relay.tv.player

import dev.relay.tv.data.TrackPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPrefsTest {
    private val codes: (String) -> List<String> = { if (it == "south") listOf("ta", "te") else listOf(it) }

    @Test
    fun typedCodesAreSplitOnCommasAndSpacesAndLowercased() {
        assertEquals(listOf("en", "hi", "bn"), TrackPrefs.codes("EN, hi  bn,"))
        assertEquals(emptyList<String>(), TrackPrefs.codes("  , "))
    }

    @Test
    fun typedAudioCodesWinOverTheProfileOrder() {
        assertEquals(listOf("hi", "en"), TrackPrefs.audioOrder("hi, en", listOf("bn"), "ta", codes))
    }

    @Test
    fun withoutTypedCodesTheProfileOrderIsUsedThenTheOldSingleLanguage() {
        assertEquals(listOf("en", "ta", "te", "hi"), TrackPrefs.audioOrder("", listOf("en", "south"), "hi", codes))
    }

    @Test
    fun theOldSingleLanguageIsSkippedWhenBlankAndNotRepeated() {
        assertEquals(listOf("en"), TrackPrefs.audioOrder(" ", listOf("en"), "", codes))
        assertEquals(listOf("en"), TrackPrefs.audioOrder("", listOf("en"), "en", codes))
    }

    @Test
    fun subtitleOrderIsJustTheTypedCodes() {
        assertEquals(listOf("en"), TrackPrefs.textOrder("EN"))
        assertEquals(emptyList<String>(), TrackPrefs.textOrder(""))
    }

    @Test
    fun aRememberedLanguageGoesFirstWithoutRepeats() {
        assertEquals(listOf("hi", "en", "bn"), TrackPrefs.preferring("hi", listOf("en", "hi", "bn")))
        assertEquals(listOf("en", "bn"), TrackPrefs.preferring(null, listOf("en", "bn")))
    }

    @Test
    fun subtitleSizesMapToTheSettingsChoices() {
        assertEquals(0.8f, TrackPrefs.subtitleScale(0))
        assertEquals(1.0f, TrackPrefs.subtitleScale(1))
        assertEquals(1.3f, TrackPrefs.subtitleScale(2))
        assertEquals(1.6f, TrackPrefs.subtitleScale(3))
        assertEquals(1.0f, TrackPrefs.subtitleScale(9))
    }

    private fun audio(lang: String?) = TrackPrefs.Selected(TrackPrefs.Kind.Audio, lang)
    private fun text(lang: String?) = TrackPrefs.Selected(TrackPrefs.Kind.Text, lang)

    @Test
    fun rememberingTakesTheFirstAudioAndFirstSubtitle() {
        val p = TrackPrefs.remembered(listOf(audio("hi"), text("en"), audio("en"), text("bn")))
        assertEquals(TrackPref(audio = "hi", text = "en", textOff = false), p)
    }

    @Test
    fun noSubtitleTrackMeansSubtitlesOff() {
        val p = TrackPrefs.remembered(listOf(audio("en")))
        assertNull(p.text)
        assertEquals(true, p.textOff)
        assertEquals("en", p.audio)
    }

    @Test
    fun aSubtitleWithoutALanguageIsRememberedAsUnd() {
        val p = TrackPrefs.remembered(listOf(audio("en"), text(null)))
        assertEquals("und", p.text)
        assertEquals(false, p.textOff)
    }

    @Test
    fun anAudioTrackWithoutALanguageDoesNotBlockALaterOneThatHasIt() {
        assertEquals("fr", TrackPrefs.remembered(listOf(audio(null), audio("fr"))).audio)
        assertNull(TrackPrefs.remembered(listOf(audio(null))).audio)
    }

    @Test
    fun nothingPlayingRemembersNothingAndSubtitlesOff() {
        assertEquals(TrackPref(audio = null, text = null, textOff = true), TrackPrefs.remembered(emptyList()))
    }
}
