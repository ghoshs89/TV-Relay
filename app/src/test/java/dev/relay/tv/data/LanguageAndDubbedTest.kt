package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAndDubbedTest {

    @Test
    fun hindiDubbedHollywoodCategoriesClassifyAsEnglishNotHindi() {
        assertEquals(Langs.EN, LanguageDetector.ofCategory("IN | HOLLYWOOD HINDI"))
        assertEquals(Langs.EN, LanguageDetector.ofCategory("HINDI DUBBED"))
        assertEquals(Langs.EN, LanguageDetector.ofCategory("IN | HINDI DUBBED"))
        assertEquals(Langs.EN, LanguageDetector.ofCategory("ASIA | INDIA - HOLLYWOOD"))
        assertEquals(Langs.EN, LanguageDetector.ofCategory("HOLLYWOOD DUAL AUDIO"))
    }

    @Test
    fun southIndianDubbedAndPureBollywoodKeepTheirOwnGroups() {
        assertEquals(Langs.SOUTH, LanguageDetector.ofCategory("SOUTH HINDI DUBBED"))
        assertEquals(Langs.SOUTH, LanguageDetector.ofCategory("SOUTH INDIAN HINDI DUBBED"))
        assertEquals(Langs.HI, LanguageDetector.ofCategory("BOLLYWOOD"))
        assertEquals(Langs.HI, LanguageDetector.ofCategory("IN | HINDI MOVIES"))
    }

    @Test
    fun titleLevelHindiDubbedOverridesToEnglishWithoutTouchingSouthIndian() {
        assertEquals(Langs.EN, LanguageDetector.ofTitle("HI - Avengers: Endgame (Hindi Dubbed)", Langs.HI))
        assertEquals(Langs.EN, LanguageDetector.ofTitle("Inception (Hindi Dubbed)", Langs.HI))
        assertEquals(Langs.EN, LanguageDetector.ofTitle("Dune [Dual Audio]", Langs.HI))
        assertEquals(Langs.HI, LanguageDetector.ofTitle("HI - Pathaan", Langs.HI))
        assertEquals(Langs.SOUTH, LanguageDetector.ofTitle("SO-IN - RRR (Hindi Dubbed)", Langs.SOUTH))
        assertEquals(Langs.SOUTH, LanguageDetector.ofTitle("Pushpa", Langs.SOUTH))
    }

    @Test
    fun isDubbedDetectsTitleAndCategoryCues() {
        assertTrue(LanguageDetector.isDubbed("Avengers (Hindi Dubbed)", "HINDI MOVIES"))
        assertTrue(LanguageDetector.isDubbed("Spider-Man [Dual Audio]", "ENGLISH"))
        assertTrue(LanguageDetector.isDubbed("Avatar", "IN | HOLLYWOOD HINDI"))
        assertTrue(LanguageDetector.isDubbed("Pushpa", "SOUTH HINDI DUBBED"))
        assertFalse(LanguageDetector.isDubbed("Pathaan", "IN | BOLLYWOOD"))
        assertFalse(LanguageDetector.isDubbed("Oppenheimer", "ENGLISH MOVIES"))
    }

    @Test
    fun englishIndianAndInternationalLiveChannelsClassifyAsEnglish() {
        assertEquals(Langs.EN, LanguageDetector.ofChannel("Star Movies", Langs.HI))
        assertEquals(Langs.EN, LanguageDetector.ofChannel("Sony Pix HD", Langs.HI))
        assertEquals(Langs.EN, LanguageDetector.ofChannel("WION", Langs.HI))
        assertEquals(Langs.EN, LanguageDetector.ofChannel("Star Sports 1", Langs.HI))
        assertEquals(Langs.SOUTH, LanguageDetector.ofChannel("Star Sports 1 Tamil", Langs.HI))
        assertEquals(Langs.HI, LanguageDetector.ofChannel("Star Gold", Langs.HI))
    }

    @Test
    fun fromApiMapsIsoAndNamesCorrectly() {
        assertEquals(Langs.EN, LanguageDetector.fromApi("en"))
        assertEquals(Langs.HI, LanguageDetector.fromApi("hi"))
        assertEquals(Langs.BN, LanguageDetector.fromApi("bn", "IN"))
        assertEquals(Langs.BD, LanguageDetector.fromApi("bn", "BD"))
        assertEquals(Langs.SOUTH, LanguageDetector.fromApi("ta"))
        assertEquals(Langs.SOUTH, LanguageDetector.fromApi("Telugu"))
        // ISO "uk" is Ukrainian (OTHER), not United Kingdom English.
        assertEquals(Langs.OTHER, LanguageDetector.fromApi("uk"))
        assertNull(LanguageDetector.fromApi("und"))
        assertNull(LanguageDetector.fromApi(null))
    }
}
