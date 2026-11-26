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

    @Test
    fun aSharedSettingsChangeTimeIsOnlyItsOwnStamp() {
        val stamps = mapOf("audioLang" to 500L)
        assertEquals(500L, SettingsSplit.changedAt(stamps, "audioLang"))
        assertEquals(null, SettingsSplit.changedAt(stamps, "tmdbKey"))
    }

    @Test
    fun aRestoreStampsEverySharedSettingButKeepsTheApiKeysStamps() {
        val r = SettingsSplit.restored(mapOf("tmdbKey" to 7L, "audioLang" to 3L), 900L, setOf("tmdbKey", "ratingsKey"))
        assertEquals(7L, r["tmdbKey"])
        assertEquals(null, r["ratingsKey"])
        assertEquals(900L, r["audioLang"])
        assertEquals(SettingsSplit.SHARED - "ratingsKey", r.keys)
    }

    /**
     * A fresh screen changes only its subtitle size (a per-screen setting) and joins a household.
     * Its untouched shared defaults must not replace the household's keys, guide and choices.
     */
    @Test
    fun aJoiningScreenWithOnlyAPerScreenChangeKeepsTheHouseholdsSettings() {
        val household = Settings(
            tmdbKey = "household-key", xmltvUrl = "http://guide.example/xml", hideAdult = true,
            langOrder = listOf("hi", "en"),
        )
        val householdDoc = SyncDoc(recs = sharedRecs(household, at = 1_000L, dev = "tv1"))

        val fresh = Settings(subSize = 3)
        val stamps = SettingsSplit.stamp(Settings(), fresh, emptyMap(), 5_000L)
        val mine = SyncDiff.export(
            sharedItems(fresh), emptyMap(), "tv2", SyncClock(), 6_000L,
            changedAt = { k, _ -> SettingsSplit.changedAt(stamps, k.removePrefix("set:")) },
            isDefault = SyncDiff::settingDefault,
        )
        val merged = SyncMerge.merge(householdDoc, SyncDoc(recs = mine), 6_000L, "2026-11-26")
        val after = SettingsSplit.withShared(fresh, merged.recs.mapValues { it.value.v!! }.mapKeys { it.key.removePrefix("set:") })

        assertEquals("household-key", after.tmdbKey)
        assertEquals("http://guide.example/xml", after.xmltvUrl)
        assertTrue(after.hideAdult)
        assertEquals(listOf("hi", "en"), after.langOrder)
        assertEquals(3, after.subSize)
    }

    @Test
    fun aSharedSettingReallyChangedOnTheJoiningScreenStillWins() {
        val household = Settings(tmdbKey = "household-key", audioLang = "hi")
        val householdDoc = SyncDoc(recs = sharedRecs(household, at = 1_000L, dev = "tv1"))

        val mineSettings = Settings(audioLang = "ta")
        val stamps = SettingsSplit.stamp(Settings(), mineSettings, emptyMap(), 5_000L)
        val mine = SyncDiff.export(
            sharedItems(mineSettings), emptyMap(), "tv2", SyncClock(), 6_000L,
            changedAt = { k, _ -> SettingsSplit.changedAt(stamps, k.removePrefix("set:")) },
            isDefault = SyncDiff::settingDefault,
        )
        val merged = SyncMerge.merge(householdDoc, SyncDoc(recs = mine), 6_000L, "2026-11-26")
        val after = SettingsSplit.withShared(mineSettings, merged.recs.mapValues { it.value.v!! }.mapKeys { it.key.removePrefix("set:") })

        assertEquals("ta", after.audioLang)
        assertEquals("household-key", after.tmdbKey)
    }

    private fun sharedItems(s: Settings): Map<String, kotlinx.serialization.json.JsonElement> {
        val o = SettingsSplit.toObject(s)
        return SettingsSplit.SHARED.mapNotNull { f -> o[f]?.let { "set:$f" to it } }.toMap()
    }

    private fun sharedRecs(s: Settings, at: Long, dev: String): Map<String, SyncRec> =
        sharedItems(s).mapValues { SyncRec(at, dev, it.value) }
}
