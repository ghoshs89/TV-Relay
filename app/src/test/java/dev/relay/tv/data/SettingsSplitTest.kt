package dev.relay.tv.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSplitTest {

    @Test
    fun everySettingIsEitherSharedOrPerScreen() {
        val all = SettingsSplit.allFields()
        val covered = SettingsSplit.SHARED + SettingsSplit.PER_SCREEN
        assertEquals("settings in neither list: ${all - covered}", emptySet<String>(), all - covered)
        assertEquals("names in a list that are not settings: ${covered - all}", emptySet<String>(), covered - all)
    }

    @Test
    fun noSettingIsInBothLists() {
        assertEquals(emptySet<String>(), SettingsSplit.SHARED.intersect(SettingsSplit.PER_SCREEN))
    }

    @Test
    fun keysAndLanguagesTravelButLipSyncAndThemeDoNot() {
        for (f in listOf("tmdbKey", "ratingsKey", "audioLang", "subtitleLang", "hiddenCats", "langOrder", "xmltvUrl")) {
            assertTrue(f, f in SettingsSplit.SHARED)
        }
        for (f in listOf("audioDelayMs", "matchFrameRate", "subSize", "neutralTheme", "keyboard", "timeshiftMinutes", "startScreen", "sleepMinutes", "userAgent", "logUntil")) {
            assertTrue(f, f in SettingsSplit.PER_SCREEN)
        }
    }

    @Test
    fun onlyChangedSharedSettingsAreStamped() {
        val old = Settings()
        val new = old.copy(audioLang = "hi", sleepMinutes = 60, tmdbKey = "abc")
        assertEquals(setOf("audioLang", "tmdbKey"), SettingsSplit.changed(old, new))
        val stamps = SettingsSplit.stamp(old, new, mapOf("hideAdult" to 5L, "audioLang" to 6L), now = 100L)
        assertEquals(mapOf("hideAdult" to 5L, "audioLang" to 100L, "tmdbKey" to 100L), stamps)
    }

    @Test
    fun aChangeToAPerScreenSettingStampsNothing() {
        val old = Settings()
        val stamps = mapOf("audioLang" to 6L)
        assertEquals(stamps, SettingsSplit.stamp(old, old.copy(audioDelayMs = 50, neutralTheme = true), stamps, 100L))
        assertEquals(stamps, SettingsSplit.stamp(old, old, stamps, 100L))
    }

    @Test
    fun collectionSettingsAreComparedByValue() {
        val old = Settings(hiddenCats = setOf("live:1"), preferredVersions = mapOf("a" to 1))
        assertEquals(emptySet<String>(), SettingsSplit.changed(old, old.copy(hiddenCats = setOf("live:1"))))
        assertEquals(setOf("hiddenCats"), SettingsSplit.changed(old, old.copy(hiddenCats = setOf("live:1", "vod:2"))))
        assertEquals(setOf("preferredVersions"), SettingsSplit.changed(old, old.copy(preferredVersions = mapOf("a" to 2))))
    }

    @Test
    fun applyingSharedValuesLeavesThisScreensOwnSettingsAlone() {
        val local = Settings(audioLang = "en", audioDelayMs = 150, subSize = 3, neutralTheme = true)
        val applied = SettingsSplit.withShared(
            local,
            mapOf(
                "audioLang" to JsonPrimitive("hi"),
                "audioDelayMs" to JsonPrimitive(-300),
                "notASetting" to JsonPrimitive("x"),
            ),
        )
        assertEquals("hi", applied.audioLang)
        assertEquals(150, applied.audioDelayMs)
        assertEquals(3, applied.subSize)
        assertTrue(applied.neutralTheme)
    }

    @Test
    fun aValueOfTheWrongShapeIsIgnoredRatherThanBreakingSettings() {
        val local = Settings(audioLang = "en", skipForwardSec = 30)
        val applied = SettingsSplit.withShared(local, mapOf("skipForwardSec" to JsonPrimitive("not a number")))
        assertEquals(local, applied)
    }

    @Test
    fun theFactoryValuesAreRecognised() {
        assertTrue(SyncDiff.settingDefault("set:audioLang", JsonPrimitive("en")))
        assertTrue(!SyncDiff.settingDefault("set:audioLang", JsonPrimitive("hi")))
        assertTrue(!SyncDiff.settingDefault("fav:p:live:1", JsonPrimitive("en")))
    }
}
